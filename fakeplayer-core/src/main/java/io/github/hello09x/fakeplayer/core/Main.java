package io.github.hello09x.fakeplayer.core;

import com.google.common.base.Throwables;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.github.hello09x.devtools.command.CommandModule;
import io.github.hello09x.devtools.core.TranslationModule;
import io.github.hello09x.devtools.core.translation.PluginTranslator;
import io.github.hello09x.devtools.core.translation.TranslationConfig;
import io.github.hello09x.devtools.core.translation.TranslatorUtils;
import io.github.hello09x.devtools.database.DatabaseModule;
import io.github.hello09x.fakeplayer.api.spi.NMSBridge;
import io.github.hello09x.fakeplayer.core.command.CommandRegistry;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.listener.FakeplayerLifecycleListener;
import io.github.hello09x.fakeplayer.core.listener.FakeplayerListener;
import io.github.hello09x.fakeplayer.core.listener.PlayerListener;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerAutofishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerReplenishManager;
import io.github.hello09x.fakeplayer.core.manager.ResidentManager;
import io.github.hello09x.fakeplayer.core.manager.WildFakeplayerManager;
import io.github.hello09x.fakeplayer.core.manager.invsee.InvseeManager;
import io.github.hello09x.fakeplayer.core.placeholder.FakeplayerPlaceholderExpansion;
import io.github.hello09x.fakeplayer.core.util.update.UpdateChecker;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class Main extends JavaPlugin {

    @Getter
    private static Main instance;

    private Injector injector;

    private long loadAt;

    /**
     * 启用是否完整走完；失败时关服清理不再刷 0011 日志
     */
    private volatile boolean enableSucceeded;

    /**
     * 生命周期阶段的错误码（0xxx），详见 ERROR-CODES.md
     */
    private final static String LOAD_PHASE = "FP-0001";
    private final static String INJECT_PHASE = "FP-0002";
    private final static String DATABASE_PHASE = "FP-0003";
    private final static String TRANSLATION_PHASE = "FP-0004";
    private final static String COMMAND_PHASE = "FP-0005";
    private final static String LISTENER_PHASE = "FP-0006";
    private final static String NMS_PHASE = "FP-0007";
    private final static String MESSENGER_PHASE = "FP-0008";
    private final static String PLACEHOLDER_PHASE = "FP-0009";
    private final static String ENABLE_PHASE = "FP-0010";
    private final static String DISABLE_PHASE = "FP-0011";

    @Override
    public void onLoad() {
        try {
            loadAt = System.currentTimeMillis();
            instance = this;
        } catch (Throwable e) {
            this.logPhaseFailure(LOAD_PHASE, "plugin load", e);
        }
    }

    @Override
    public void onEnable() {
        try {
            this.enable();
        } catch (Throwable e) {
            // 具体失败阶段已由 phase() 记录，这里只补一条总览并关闭插件
            getLogger().severe("[%s] fakeplayer failed to enable, disabling plugin".formatted(ENABLE_PHASE));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enable() {
        injector = this.phase(INJECT_PHASE, "dependency injection (config/database/translation modules)", () -> Guice.createInjector(
                new FakeplayerModule(),
                new CommandModule(),
                new DatabaseModule(),
                new TranslationModule(new TranslationConfig(
                        "message/message",
                        TranslatorUtils.getDefaultLocale(Main.getInstance())))
        ));

        this.phase(INJECT_PHASE, "config.yml", () -> injector.getInstance(FakeplayerConfig.class));
        this.phase(DATABASE_PHASE, "database (SQLite/HikariCP)", () -> injector.getInstance(DataSource.class));
        this.phase(TRANSLATION_PHASE, "translations", () -> injector.getInstance(PluginTranslator.class));
        this.phase(NMS_PHASE, "NMS bridge for Minecraft " + Bukkit.getMinecraftVersion(), () -> injector.getInstance(NMSBridge.class));

        this.runPhase(COMMAND_PHASE, "command registration (CommandAPI)",
                () -> injector.getInstance(CommandRegistry.class).register());

        this.runPhase(MESSENGER_PHASE, "plugin message channels", () -> {
            var messenger = getServer().getMessenger();
            messenger.registerIncomingPluginChannel(this, "BungeeCord", injector.getInstance(WildFakeplayerManager.class));
            messenger.registerOutgoingPluginChannel(this, "BungeeCord");
        });

        this.runPhase(LISTENER_PHASE, "event listeners", () -> {
            var manager = getServer().getPluginManager();
            manager.registerEvents(injector.getInstance(PlayerListener.class), this);
            manager.registerEvents(injector.getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticListener.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerLifecycleListener.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerListener.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerAutofishManager.class), this);
            manager.registerEvents(injector.getInstance(FakeplayerReplenishManager.class), this);
            manager.registerEvents(injector.getInstance(InvseeManager.class), this);
        });

        // PlaceholderAPI 是可选依赖，失败不影响插件本体
        try {
            this.runPhase(PLACEHOLDER_PHASE, "PlaceholderAPI expansion", () -> {
                var placeholderExpansion = injector.getInstance(FakeplayerPlaceholderExpansion.class);
                if (placeholderExpansion != null && placeholderExpansion.register()) {
                    getServer().getPluginManager().registerEvents(placeholderExpansion, this);
                    getLogger().info("Successfully registered PlaceholderExpansion");
                }
            });
        } catch (Throwable e) {
            getLogger().warning("[%s] PlaceholderAPI expansion disabled, plugin keeps running".formatted(PLACEHOLDER_PHASE));
        }

        if (injector.getInstance(FakeplayerConfig.class).isCheckForUpdates()) {
            checkForUpdatesAsync();
        }

        // 延迟 1 秒恢复驻留假人，等待世界与其他插件初始化完成
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                injector.getInstance(ResidentManager.class).restoreAll();
            } catch (Throwable e) {
                getLogger().warning("[FP-7006] Failed to restore resident fake players: " + e);
            }
        }, 20L);

        this.enableSucceeded = true;
        getLogger().info("Enabled in %d ms".formatted(System.currentTimeMillis() - loadAt));
    }

    // ------------------------------------------------------------------
    // 阶段执行与错误码
    // ------------------------------------------------------------------

    private <T> T phase(@NotNull String code, @NotNull String what, @NotNull Supplier<T> action) {
        try {
            return action.get();
        } catch (Throwable e) {
            this.logPhaseFailure(code, what, e);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(what, e);
        }
    }

    private void runPhase(@NotNull String code, @NotNull String what, @NotNull Runnable action) {
        this.phase(code, what, () -> {
            action.run();
            return null;
        });
    }

    private void logPhaseFailure(@NotNull String code, @NotNull String what, @NotNull Throwable e) {
        var root = Throwables.getRootCause(e);
        getLogger().severe("[%s] Failed during %s: %s".formatted(code, what, root));
        getLogger().severe(Throwables.getStackTraceAsString(e));
    }

    private void shutdown(@NotNull String code, @NotNull String what, @NotNull Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            if (this.enableSucceeded) {
                getLogger().warning("[%s] Failed to shut down %s: %s".formatted(code, what, Throwables.getRootCause(e)));
            }
        }
    }

    public void checkForUpdatesAsync() {
        CompletableFuture.runAsync(() -> {
            var meta = this.getPluginMeta();
            var checker = new UpdateChecker("tanyaofei", "minecraft-fakeplayer");
            try {
                var release = checker.getLastRelease();

                var current = meta.getVersion();
                var other = release.getTagName();
                if (other.charAt(0) == 'v') {
                    other = other.substring(1);
                }

                if (UpdateChecker.isNew(current, other)) {
                    var log = getLogger();
                    log.info("New version: " + release.getTagName());
                    log.info("Address: " + meta.getWebsite());
                    log.info("Update Log");
                    for (var line : release.getBody().split("\n")) {
                        log.info("\t" + line);
                    }
                }

            } catch (Throwable e) {
                getLogger().warning("[FP-9006] Error on checking for updates: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (injector != null) {
            this.shutdown(DISABLE_PHASE, "invsee manager", () -> injector.getInstance(InvseeManager.class).close());
            this.shutdown(DISABLE_PHASE, "spawn coordinator", () -> injector.getInstance(io.github.hello09x.fakeplayer.core.manager.SpawnCoordinator.class).close());
            this.shutdown(DISABLE_PHASE, "quit command queue", () -> injector.getInstance(io.github.hello09x.fakeplayer.core.manager.QuitCommandManager.class).flushAll());
            this.shutdown(DISABLE_PHASE, "diagnostic manager", () -> injector.getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class).flush());
            this.shutdown(DISABLE_PHASE, "yaml save queue", () -> injector.getInstance(io.github.hello09x.fakeplayer.core.util.YamlSaveQueue.class).close());
        }
        this.shutdown(MESSENGER_PHASE, "plugin message channels", () -> {
            var messenger = getServer().getMessenger();
            messenger.unregisterIncomingPluginChannel(this);
            messenger.unregisterOutgoingPluginChannel(this);
        });
    }

    public static @NotNull Injector getInjector() {
        return instance.injector;
    }

}
