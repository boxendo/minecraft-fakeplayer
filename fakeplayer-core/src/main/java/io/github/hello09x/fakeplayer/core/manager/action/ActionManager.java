package io.github.hello09x.fakeplayer.core.manager.action;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.api.spi.ActionSetting;
import io.github.hello09x.fakeplayer.api.spi.ActionTicker;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import io.github.hello09x.fakeplayer.api.spi.NMSBridge;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Singleton
public class ActionManager {

    private final static Logger log = Main.getInstance().getLogger();

    private final Map<UUID, Map<ActionType, ActionTicker>> managers = new HashMap<>();

    private final NMSBridge bridge;
    private final DiagnosticManager diagnostics;
    private final Map<ActionTicker, Integer> failures = new IdentityHashMap<>();
    private static final int MAX_FAILURES = 3;


    @Inject
    public ActionManager(NMSBridge bridge, DiagnosticManager diagnostics) {
        this.bridge = bridge;
        this.diagnostics = diagnostics;
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), this::tick, 0, 1);
    }

    public boolean hasActiveAction(
            @NotNull Player player,
            @NotNull ActionType action
    ) {
        return Optional.ofNullable(this.managers.get(player.getUniqueId()))
                       .map(manager -> manager.get(action))
                       .filter(ac -> ac.getSetting().remains != 0)
                       .isPresent();
    }

    public @NotNull @Unmodifiable Set<ActionType> getActiveActions(@NotNull Player player) {
        var manager = this.managers.get(player.getUniqueId());
        if (manager == null || managers.isEmpty()) {
            return Collections.emptySet();
        }

        return manager.entrySet()
                      .stream()
                      .filter(actions -> actions.getValue().getSetting().remains != 0)
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toSet());
    }

    /**
     * 获取玩家当前所有正在执行的动作及其设置（用于重启后恢复）
     *
     * @param player 玩家
     * @return 动作 -> 设置
     */
    public @NotNull Map<ActionType, ActionSetting> getActions(@NotNull Player player) {
        var manager = this.managers.get(player.getUniqueId());
        if (manager == null || manager.isEmpty()) {
            return Collections.emptyMap();
        }

        var result = new LinkedHashMap<ActionType, ActionSetting>();
        for (var entry : manager.entrySet()) {
            var setting = entry.getValue().getSetting();
            if (setting.remains == 0) {
                continue;
            }
            result.put(entry.getKey(), setting.clone());
        }
        return result;
    }

    public void setAction(
            @NotNull Player player,
            @NotNull ActionType action,
            @NotNull ActionSetting setting
    ) {
        var ticker = bridge.createAction(player, action, setting);
        var actions = this.managers.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>());
        var old = actions.remove(action);
        if (old != null) stopSafely(old);
        actions.put(action, ticker);
    }

    public void stop(@NotNull Player player) {
        var actions = managers.remove(player.getUniqueId());
        if (actions != null) new ArrayList<>(actions.values()).forEach(this::stopSafely);
    }

    private void stopSafely(ActionTicker ticker) {
        failures.remove(ticker);
        try { ticker.stop(); }
        catch (Throwable error) { log.log(Level.WARNING, "Failed to clean up a stopped fakeplayer action", error); }
    }

    public void tick() {
        // Actions can synchronously invoke plugin events which replace/stop actions.
        for (var id : new ArrayList<>(managers.keySet())) {
            var actions = managers.get(id);
            if (actions == null) continue;
            var player = Bukkit.getPlayer(id);
            if (player == null || !player.isValid()) {
                managers.remove(id, actions);
                new ArrayList<>(actions.values()).forEach(this::stopSafely);
                continue;
            }
            for (var entry : new ArrayList<>(actions.entrySet())) {
                var type = entry.getKey();
                var ticker = entry.getValue();
                if (managers.get(id) != actions || actions.get(type) != ticker) continue;
                boolean attempted = ticker.getSetting().wait <= 0;
                try {
                    if (ticker.tick()) {
                        actions.remove(type, ticker);
                        failures.remove(ticker);
                    } else if (attempted) {
                        // Cooldown ticks are not successful attempts; they must not hide recurring errors.
                        failures.remove(ticker);
                    }
                } catch (Throwable error) {
                    if (managers.get(id) != actions || actions.get(type) != ticker) continue;
                    int count = failures.merge(ticker, 1, Integer::sum);
                    if (count == 1) log.log(Level.WARNING,
                            "Fakeplayer " + player.getName() + " action " + type + " failed (1/" + MAX_FAILURES + ")", error);
                    if (count >= MAX_FAILURES) {
                        actions.remove(type, ticker);
                        stopSafely(ticker);
                        var message = type + " 连续失败 " + count + " 次，已停止；重新执行该动作命令可重试。";
                        diagnostics.note(id, "ACTION_STOPPED", message + " " + error);
                        log.warning("Fakeplayer " + player.getName() + ": " + message);
                    }
                }
            }
            if (actions.isEmpty()) managers.remove(id, actions);
        }
    }
}
