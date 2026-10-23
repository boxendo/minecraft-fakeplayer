package io.github.hello09x.fakeplayer.core.util;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

/** Deterministic fault-injection tests; no running Bukkit server required. */
public final class YamlSaveQueueTest {
    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
        System.out.println("PASS " + text);
    }
    private static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
    }
    public static void main(String[] args) throws Exception {
        var logger = Logger.getLogger("YamlSaveQueueTest"); logger.setUseParentHandlers(false);
        var path = Path.of("test.yml");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var writes = new CopyOnWriteArrayList<Map<String,Object>>();
        var thread = new AtomicReference<String>();
        var writer = new YamlSaveQueue(logger, (p, snapshot) -> {
            thread.set(Thread.currentThread().getName());
            if (writes.isEmpty()) { entered.countDown(); await(release); }
            writes.add(snapshot);
        });
        var row = new LinkedHashMap<String,Object>(); row.put("name", "original"); row.put("nullable", null);
        writer.submit(path, Map.of("players", List.of(row)));
        await(entered);
        row.put("name", "mutated");
        for (int i = 1; i <= 100; i++) writer.submit(path, Map.of("revision", i));
        release.countDown(); writer.close();
        check(thread.get().equals("Fakeplayer-YAML"), "encoding/write sink runs on dedicated worker");
        check(((Map<?,?>)((List<?>)writes.getFirst().get("players")).getFirst()).get("name").equals("original"), "submitted nested snapshot detached from later mutations");
        check(writes.size() == 2 && writes.getLast().get("revision").equals(100), "100 pending revisions coalesce; close flushes latest after in-flight write");
        try { writer.submit(path, Map.of()); throw new AssertionError("accepted after close"); }
        catch (IllegalStateException expected) { check(true, "closed writer rejects new submissions"); }

        var retry = new CountDownLatch(1); var calls = new AtomicInteger();
        var retryWriter = new YamlSaveQueue(logger, (p, snapshot) -> {
            if (calls.incrementAndGet() == 1) throw new java.io.IOException("fixture disk failure");
            retry.countDown();
        });
        retryWriter.submit(path, Map.of("revision", 7)); await(retry);
        retryWriter.submit(path, Map.of("revision", 7)); retryWriter.close();
        check(calls.get() == 2, "failed snapshot retried automatically; unchanged snapshot does not rewrite");

        var failureEntered = new CountDownLatch(1); var failureRelease = new CountDownLatch(1);
        var last = new AtomicReference<Map<String,Object>>(); var attempts = new AtomicInteger();
        var recovering = new YamlSaveQueue(logger, (p, snapshot) -> {
            if (attempts.incrementAndGet() == 1) {
                failureEntered.countDown(); await(failureRelease);
                throw new java.io.IOException("fixture old write fails");
            }
            last.set(snapshot);
        });
        recovering.submit(path, Map.of("revision", 1)); await(failureEntered);
        recovering.submit(path, Map.of("revision", 2)); failureRelease.countDown(); recovering.close();
        check(last.get().get("revision").equals(2), "failed old write cannot replace newer snapshot during shutdown");

        var broken = new YamlSaveQueue(logger, (p, snapshot) -> { throw new java.io.IOException("persistent disk failure"); });
        broken.submit(path, Map.of("revision", 1));
        try { broken.close(); throw new AssertionError("silent failed flush"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("Unsaved"), "persistent shutdown save failure explicitly reported"); }
    }
}
