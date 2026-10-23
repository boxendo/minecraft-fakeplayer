package io.github.hello09x.fakeplayer.core.manager.diagnostic;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerList;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

@Singleton
public class DiagnosticListener implements Listener {
    private final FakeplayerList players;
    private final DiagnosticManager diagnostics;
    private final io.github.hello09x.fakeplayer.core.manager.SpawnCoordinator coordinator;
    @Inject public DiagnosticListener(FakeplayerList players,DiagnosticManager diagnostics,io.github.hello09x.fakeplayer.core.manager.SpawnCoordinator coordinator) {
        this.players=players; this.diagnostics=diagnostics; this.coordinator=coordinator;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void worldLimit(PlayerTeleportEvent event) {
        var fake=players.getByUUID(event.getPlayer().getUniqueId());
        if (fake==null || fake.getPlayer()!=event.getPlayer() || event.getTo()==null) return;
        var to=event.getTo().getWorld();
        if (!event.getFrom().getWorld().equals(to) && !coordinator.mayEnter(fake,to)) {
            event.setCancelled(true);
            diagnostics.event(fake,"WORLD_LIMIT","插件的单世界假人上限拒绝此跨世界传送",event.getHandlers());
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void kicked(PlayerKickEvent event) {
        var fake=players.getByUUID(event.getPlayer().getUniqueId());
        if (fake==null || fake.getPlayer()!=event.getPlayer()) return;
        diagnostics.event(event.getPlayer().getUniqueId(),"KICK",
                "cancelled="+event.isCancelled()+"; "+PlainTextComponentSerializer.plainText().serialize(event.reason()),event.getHandlers());
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void teleported(PlayerTeleportEvent event) {
        var fake=players.getByUUID(event.getPlayer().getUniqueId());
        if (fake==null || fake.getPlayer()!=event.getPlayer() || !event.isCancelled()) return;
        diagnostics.event(event.getPlayer().getUniqueId(),"TELEPORT",
                "cancelled=true; cause="+event.getCause()+"; 传送已被取消，监听插件仅是排查线索",event.getHandlers());
    }
}
