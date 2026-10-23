package io.github.hello09x.fakeplayer.core.util;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** One writer for detached YAML snapshots. Never pass Bukkit entities or configurations to it. */
@Singleton
public final class YamlSaveQueue implements AutoCloseable {
    @FunctionalInterface
    interface Sink { void write(Path path, Map<String, Object> snapshot) throws Exception; }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "Fakeplayer-YAML");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Path, Map<String, Object>> pending = new LinkedHashMap<>();
    private final Map<Path, Map<String, Object>> latest = new HashMap<>();
    private final Set<Path> failed = new HashSet<>();
    private final Logger log;
    private final Sink sink;
    private ScheduledFuture<?> scheduled;
    private boolean running, closing;

    @Inject public YamlSaveQueue() {
        this(Main.getInstance().getLogger(), (path, snapshot) -> {
            var yaml = new YamlConfiguration();
            snapshot.forEach(yaml::set);
            AtomicYamlFiles.write(yaml, path);
        });
    }

    YamlSaveQueue(Logger log, Sink sink) { this.log = log; this.sink = sink; }

    public synchronized void submit(Path path, Map<String, Object> data) {
        if (closing) throw new IllegalStateException("YAML writer is closing");
        path = path.toAbsolutePath().normalize();
        var snapshot = copyMap(data);
        if (snapshot.equals(latest.get(path))) return;
        latest.put(path, snapshot);
        pending.put(path, snapshot);
        if (!running && scheduled == null) schedule(0);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), copyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) return copyMap(map);
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(YamlSaveQueue::copyValue).toList());
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Double
                || value instanceof Float || value instanceof Short || value instanceof Byte) return value;
        throw new IllegalArgumentException("Unsupported YAML snapshot value: " + value.getClass().getName());
    }

    private void schedule(long delay) { scheduled = executor.schedule(this::drain, delay, TimeUnit.MILLISECONDS); }

    private void drain() {
        Map<Path, Map<String, Object>> batch;
        synchronized (this) {
            scheduled = null;
            running = true;
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        for (var entry : batch.entrySet()) {
            try {
                sink.write(entry.getKey(), entry.getValue());
                synchronized (this) { failed.remove(entry.getKey()); }
            } catch (Exception error) {
                synchronized (this) {
                    // A newer submission always wins over the failed snapshot.
                    pending.putIfAbsent(entry.getKey(), entry.getValue());
                    if (failed.add(entry.getKey())) log.log(Level.SEVERE,
                            "Cannot save " + entry.getKey().getFileName() + "; snapshot retained for retry", error);
                }
            }
        }
        synchronized (this) {
            running = false;
            if (!closing && !pending.isEmpty()) schedule(failed.isEmpty() ? 0 : 1000);
            notifyAll();
        }
    }

    /** Wait only during plugin shutdown; live snapshots and native player saves stay on the server thread. */
    @Override public void close() {
        Future<?> finalFlush;
        synchronized (this) {
            if (closing) return;
            closing = true;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
            // Serialized behind any write already in flight, including its retry bookkeeping.
            finalFlush = executor.submit(this::drain);
            executor.shutdown();
        }
        try {
            finalFlush.get(30, TimeUnit.SECONDS);
            synchronized (this) {
                if (!pending.isEmpty()) throw new IllegalStateException("Unsaved YAML snapshots: " + pending.keySet());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while flushing YAML snapshots", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IllegalStateException("YAML shutdown flush did not complete", error);
        }
    }
}
