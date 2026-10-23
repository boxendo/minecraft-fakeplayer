package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.util.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;

/** Server-thread owned. Keeps quit command context after the fake player leaves the registry. */
@Singleton
public final class QuitCommandManager {
    private static final class Pending {
        final Player player;
        final List<String> commands;
        BukkitTask task;
        boolean quitObserved;

        Pending(Player player, List<String> commands) { this.player = player; this.commands = commands; }
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public void capture(Player player, String creator, List<String> commands) {
        var formatted = List.copyOf(Commands.formatCommands(commands, "%p", player.getName(),
                "%u", player.getUniqueId().toString(), "%c", creator));
        if (formatted.isEmpty()) return;
        var key = player.getName().toLowerCase(Locale.ROOT);
        if (pending.containsKey(key)) throw new IllegalStateException("Unfinished quit commands for " + player.getName());
        pending.put(key, new Pending(player, formatted));
    }

    public void afterQuit(Player player) {
        var key = player.getName().toLowerCase(Locale.ROOT);
        var job = pending.get(key);
        if (job == null || job.player != player) return;
        job.quitObserved = true;
        // Plugin-disable quits are flushed from Main.onDisable after removal has returned.
        if (Main.getInstance().isEnabled() && !Bukkit.isStopping()) {
            job.task = Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                if (pending.get(key) == job) flush(key, false);
            });
        }
    }

    /** Cleanup must precede pre-spawn registration commands, including same-name case changes. */
    public void beforeSpawn(String name) {
        flush(name.toLowerCase(Locale.ROOT), true);
    }

    private void flush(String key, boolean spawning) {
        var job = pending.get(key);
        if (job == null) return;
        if (!job.quitObserved || job.player.isOnline()) {
            throw new IllegalStateException("Player quit has not completed: " + job.player.getName());
        }
        pending.remove(key, job);
        if (job.task != null) job.task.cancel();
        // Never apply an old destructive cleanup to a replacement player managed by another plugin.
        if (Bukkit.getPlayerExact(job.player.getName()) != null) {
            var message = "Skipped stale after-quit commands: name is online again: " + job.player.getName();
            Main.getInstance().getLogger().warning(message);
            if (spawning) throw new IllegalStateException(message);
            return;
        }
        for (var command : job.commands) {
            try {
                if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    Main.getInstance().getLogger().info("Dispatched command: " + command);
                } else {
                    Main.getInstance().getLogger().warning("Failed after-quit command for " + job.player.getName() + ": " + command);
                }
            } catch (Exception error) {
                Main.getInstance().getLogger().log(Level.WARNING,
                        "Failed after-quit command for " + job.player.getName() + ": " + command, error);
            }
        }
    }

    public void flushAll() {
        for (var key : new ArrayList<>(pending.keySet())) {
            try { flush(key, false); }
            catch (Exception error) { Main.getInstance().getLogger().log(Level.WARNING, "Could not finish quit commands for " + key, error); }
        }
    }
}
