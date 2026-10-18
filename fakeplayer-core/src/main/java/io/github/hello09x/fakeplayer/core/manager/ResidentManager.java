package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.devtools.core.utils.Exceptions;
import io.github.hello09x.fakeplayer.api.spi.ActionSetting;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.entity.SpawnOption;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.manager.naming.SequenceName;
import io.github.hello09x.fakeplayer.core.util.OfflineCommandSender;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * 假人驻留管理。
 *
 * <p>开启驻留的假人会在插件关闭时被写入 {@code resident.yml}，
 * 服务器下次启动后按保存的世界、坐标、朝向和动作重新生成。</p>
 */
@Singleton
public class ResidentManager {

    private final static Logger log = Main.getInstance().getLogger();

    private final static String FILE_NAME = "resident.yml";

    private final FakeplayerList playerList;

    private final ActionManager actionManager;

    private final File file;

    /**
     * 插件关闭流程中，忽略 quit 事件带来的“取消驻留”
     */
    private volatile boolean shuttingDown;

    @Inject
    public ResidentManager(FakeplayerList playerList, ActionManager actionManager) {
        this.playerList = playerList;
        this.actionManager = actionManager;
        this.file = new File(Main.getInstance().getDataFolder(), FILE_NAME);

        // 崩溃兜底：每 10 秒快照一次
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), this::saveQuietly, 200L, 200L);
    }

    /**
     * 判断假人是否开启了驻留
     */
    public boolean isResident(@NotNull Player player) {
        var fake = this.playerList.getByUUID(player.getUniqueId());
        return fake != null && fake.isResident();
    }

    /**
     * 设置假人是否驻留，并立即落盘
     */
    public void setResident(@NotNull Player player, boolean resident) {
        var fake = this.playerList.getByUUID(player.getUniqueId());
        if (fake == null) {
            return;
        }

        fake.setResident(resident);
        if (resident) {
            this.saveQuietly();
        } else {
            this.removeEntry(player.getUniqueId());
        }
    }

    /**
     * 假人被移除 / 退出时调用，取消其驻留记录。
     * <p>插件关闭时不移除，否则下次启动就无法恢复了。</p>
     */
    public void forget(@NotNull UUID uuid) {
        if (this.shuttingDown) {
            return;
        }
        this.removeEntry(uuid);
    }

    /**
     * 插件关闭前调用：先快照，再标记 {@link #shuttingDown}，之后踢人不会删掉记录。
     */
    public void onDisable() {
        this.saveQuietly();
        this.shuttingDown = true;
    }

    /**
     * 服务器启动后恢复所有驻留假人。
     */
    public void restoreAll() {
        if (!this.file.exists()) {
            return;
        }

        YamlConfiguration config;
        try {
            config = YamlConfiguration.loadConfiguration(this.file);
        } catch (Throwable e) {
            log.warning("Failed to read " + FILE_NAME + ": " + e.getMessage());
            return;
        }

        var entries = config.getMapList("players");
        if (entries.isEmpty()) {
            return;
        }

        log.info("Restoring %d resident fake player(s)".formatted(entries.size()));
        for (var entry : entries) {
            try {
                this.restore(entry);
            } catch (Throwable e) {
                log.warning("Failed to restore resident fake player: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private void saveQuietly() {
        Exceptions.suppress(Main.getInstance(), this::save);
    }

    public void save() {
        var residents = this.playerList.getAll().stream().filter(Fakeplayer::isResident).toList();
        if (residents.isEmpty() && !this.file.exists()) {
            return;
        }

        var config = new YamlConfiguration();
        var entries = new ArrayList<Map<String, Object>>(residents.size());
        for (var fake : residents) {
            var player = fake.getPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            entries.add(this.toMap(fake, player));
        }

        config.set("players", entries);
        try {
            config.save(this.file);
        } catch (IOException e) {
            log.warning("Failed to save " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private @NotNull Map<String, Object> toMap(@NotNull Fakeplayer fake, @NotNull Player player) {
        var loc = player.getLocation();
        var map = new LinkedHashMap<String, Object>();
        map.put("uuid", fake.getUUID().toString());
        map.put("name", fake.getName());
        map.put("creator-uuid", fake.getCreator() instanceof Player p ? p.getUniqueId().toString() : null);
        map.put("creator-name", fake.getCreator().getName());
        map.put("creator-ip", fake.getCreatorIp());
        map.put("world", loc.getWorld().getName());
        map.put("x", loc.getX());
        map.put("y", loc.getY());
        map.put("z", loc.getZ());
        map.put("yaw", loc.getYaw());
        map.put("pitch", loc.getPitch());
        map.put("sprinting", player.isSprinting());
        map.put("sneaking", player.isSneaking());
        map.put("invulnerable", player.isInvulnerable());
        map.put("collidable", player.isCollidable());
        map.put("pickup-items", player.getCanPickupItems());
        map.put("wolverine", player.hasPotionEffect(PotionEffectType.REGENERATION));
        map.put("replenish", Main.getInjector().getInstance(FakeplayerReplenishManager.class).isReplenish(player));
        map.put("autofish", Main.getInjector().getInstance(FakeplayerAutofishManager.class).isAutofish(player));

        var actions = new LinkedHashMap<String, Map<String, Object>>();
        for (var entry : this.actionManager.getActions(player).entrySet()) {
            var setting = entry.getValue();
            var node = new LinkedHashMap<String, Object>();
            node.put("maximum", setting.maximum);
            node.put("remains", setting.remains);
            node.put("interval", setting.interval);
            node.put("wait", setting.wait);
            actions.put(entry.getKey().name(), node);
        }
        map.put("actions", actions);
        return map;
    }

    private void removeEntry(@NotNull UUID uuid) {
        if (!this.file.exists()) {
            return;
        }

        try {
            var config = YamlConfiguration.loadConfiguration(this.file);
            var entries = new ArrayList<>(config.getMapList("players"));
            var removed = entries.removeIf(entry -> uuid.toString().equals(String.valueOf(entry.get("uuid"))));
            if (!removed) {
                return;
            }
            config.set("players", entries);
            config.save(this.file);
        } catch (Throwable e) {
            log.warning("Failed to update " + FILE_NAME + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 恢复
    // ------------------------------------------------------------------

    private void restore(@NotNull Map<?, ?> entry) {
        var uuid = UUID.fromString(String.valueOf(entry.get("uuid")));
        var name = String.valueOf(entry.get("name"));
        var worldName = String.valueOf(entry.get("world"));
        var world = Bukkit.getWorld(worldName);
        if (world == null) {
            log.warning("Skip restoring '%s': world '%s' is not loaded".formatted(name, worldName));
            return;
        }
        if (Bukkit.getPlayerExact(name) != null) {
            log.warning("Skip restoring '%s': name is occupied".formatted(name));
            return;
        }

        var creatorName = Optional.ofNullable(entry.get("creator-name")).map(String::valueOf).orElse("CONSOLE");
        var creatorUuid = this.parseUUID(Optional.ofNullable(entry.get("creator-uuid")).map(String::valueOf).orElse(null));
        CommandSender creator = Optional.ofNullable(creatorUuid)
                                        .map(Bukkit::getPlayer)
                                        .map(p -> (CommandSender) p)
                                        .orElseGet(() -> new OfflineCommandSender(creatorName, creatorUuid));

        var creatorIp = Optional.ofNullable(entry.get("creator-ip")).map(String::valueOf).orElse("127.0.0.1");
        var sequenceName = new SequenceName("restored", 0, uuid, name);
        var fake = new Fakeplayer(creator, creatorIp, sequenceName, 0);
        fake.setResident(true);
        this.playerList.add(fake);

        var location = new Location(
                world,
                this.toDouble(entry.get("x")),
                this.toDouble(entry.get("y")),
                this.toDouble(entry.get("z")),
                (float) this.toDouble(entry.get("yaw")),
                (float) this.toDouble(entry.get("pitch"))
        );

        var option = new SpawnOption(
                location,
                this.toBoolean(entry, "invulnerable"),
                this.toBoolean(entry, "collidable"),
                this.toBoolean(entry, "look-at-entity"),
                this.toBoolean(entry, "pickup-items"),
                false,
                this.toBoolean(entry, "replenish"),
                this.toBoolean(entry, "autofish"),
                this.toBoolean(entry, "wolverine"),
                true
        );

        var actions = this.readActions(entry);
        var states = new boolean[]{
                this.toBoolean(entry, "sprinting"),
                this.toBoolean(entry, "sneaking")
        };

        fake.spawnAsync(option).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                log.warning("Failed to restore resident fake player '%s': %s".formatted(name, throwable));
                this.playerList.remove(fake);
                return;
            }
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> this.applyState(fake.getPlayer(), states, actions));
        });
    }

    private void applyState(@NotNull Player player, boolean[] states, @NotNull Map<ActionType, ActionSetting> actions) {
        if (!player.isOnline()) {
            return;
        }

        player.setSprinting(states[0]);
        player.setSneaking(states[1]);

        for (var entry : actions.entrySet()) {
            this.actionManager.setAction(player, entry.getKey(), entry.getValue());
        }

        log.info("Restored resident fake player '%s' at %s".formatted(player.getName(), player.getLocation()));
    }

    private @NotNull Map<ActionType, ActionSetting> readActions(@NotNull Map<?, ?> entry) {
        var result = new LinkedHashMap<ActionType, ActionSetting>();
        if (!(entry.get("actions") instanceof Map<?, ?> actions)) {
            return result;
        }

        for (var raw : actions.entrySet()) {
            ActionType type;
            try {
                type = ActionType.valueOf(String.valueOf(raw.getKey()));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!(raw.getValue() instanceof Map<?, ?> node)) {
                continue;
            }
            var setting = new ActionSetting(
                    this.toInt(node.get("maximum"), -1),
                    this.toInt(node.get("interval"), 0),
                    this.toInt(node.get("wait"), 0)
            );
            setting.remains = this.toInt(node.get("remains"), setting.maximum);
            result.put(type, setting);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private @Nullable UUID parseUUID(@Nullable String text) {
        if (text == null || text.isBlank() || "null".equals(text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private double toDouble(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int toInt(@Nullable Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean toBoolean(@NotNull Map<?, ?> map, @NotNull String key) {
        var value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

}
