package io.github.hello09x.fakeplayer.core.util;

import io.github.hello09x.fakeplayer.core.Main;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Completes directly from the server task; never parks a common-pool worker. */
public final class ServerTasks {
    private ServerTasks() {}

    public static CompletableFuture<Void> async(Runnable action) {
        var result = new CompletableFuture<Void>();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                try { action.run(); result.complete(null); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (Throwable error) { result.completeExceptionally(error); }
        return result;
    }

    public static <T> CompletableFuture<T> call(Supplier<T> action) {
        var result = new CompletableFuture<T>();
        Runnable task = () -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        };
        try {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(Main.getInstance(), task);
            }
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }
}
