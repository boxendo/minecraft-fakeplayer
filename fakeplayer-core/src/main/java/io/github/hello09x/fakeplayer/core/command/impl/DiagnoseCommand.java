package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.command.Permission;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.manager.*;
import io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import java.io.File;
import java.util.*;

@Singleton
public class DiagnoseCommand {
    @Inject private DiagnosticManager diagnostics;
    @Inject private ResidentManager residents;
    @Inject private SpawnCoordinator coordinator;
    @Inject private FakeplayerConfig config;
    @Inject private io.github.hello09x.fakeplayer.api.spi.NMSBridge nms;

    public void diagnose(CommandSender sender,CommandArguments args) throws WrapperCommandSyntaxException {
        var name=(String)args.get("record");
        if (name==null) {
            line(sender,"Permissions: spawn="+sender.hasPermission(Permission.spawn)+"; resident="+sender.hasPermission(Permission.resident)+
                    "; restore="+sender.hasPermission(Permission.residentRestore)+"; forget="+sender.hasPermission(Permission.residentForget)+
                    "; manage-others="+sender.hasPermission(Permission.manageOthers));
            if (sender.hasPermission(Permission.diagnoseServer)) server(sender);
            sender.sendMessage(Component.translatable("fakeplayer.diagnose.recent"));
            var entries=new ArrayList<>(diagnostics.visible(sender));
            entries.sort(Comparator.comparingLong((DiagnosticManager.Entry e)->e.changedAt).reversed());
            entries.stream().limit(10).forEach(e->line(sender,e.name+" | "+ResidentManagementCommand.state(e.stage)+" | "+e.reason));
            return;
        }
        var e=diagnostics.find(sender,name);
        var resident=residents.find(sender,name);
        if (e==null && resident==null) throw CommandAPI.failWithString(io.github.hello09x.devtools.core.utils.ComponentUtils.toString(Component.translatable("fakeplayer.resident.not-found"), io.github.hello09x.devtools.core.translation.TranslatorUtils.getLocale(sender)));
        if (e==null) {
            line(sender,resident.name()+" | "+ResidentManagementCommand.state(resident.state())+" | "+resident.reason());
            return;
        }
        line(sender,e.name+" | "+e.uuid+" | "+ResidentManagementCommand.state(e.stage));
        line(sender,"Owner: "+e.creatorName+" | "+e.creatorId);
        line(sender,"Operation: "+e.operation+"; "+e.reason);
        line(sender,"Attempt: "+DiagnosticManager.time(e.lastAttempt));
        line(sender,"Save: "+DiagnosticManager.time(e.lastSave)+"; restore: "+DiagnosticManager.time(e.lastRestore));
        if (!e.lastError.isBlank()) line(sender,"Last failure ["+e.failedStage+"]: "+e.lastError);
        line(sender,"Events (listener plugins are leads, not attribution):");
        e.events.forEach(event->line(sender,event));
        if (sender.hasPermission(Permission.diagnoseServer)) {
            var folder=nms.fromServer(Bukkit.getServer()).getPlayerDataFolder();
            if (folder!=null) {
                var data=new File(folder,e.uuid+".dat");
                line(sender,"Native playerdata: "+data.getAbsolutePath()+"; exists="+data.isFile()+"; modified="+DiagnosticManager.time(data.lastModified()));
            } else line(sender,"Native playerdata path is unavailable in this adapter.");
        }
        sender.sendMessage(Component.translatable("fakeplayer.diagnose.leads"));
    }

    private void server(CommandSender sender) {
        line(sender,"Fakeplayer "+Main.getInstance().getPluginMeta().getVersion()+" | "+Bukkit.getVersion()+" | Java "+System.getProperty("java.version"));
        line(sender,"Queue="+coordinator.queued()+"; active="+coordinator.running()+"; target chunk loads="+coordinator.loading());
        line(sender,"Budget: spawn/tick="+config.getSpawnsPerTick()+"; concurrent="+config.getMaxConcurrentSpawns()+
                "; chunk requests/tick="+config.getChunkLoadsPerTick()+"; concurrent loads="+config.getMaxConcurrentChunkLoads());
        Bukkit.getWorlds().forEach(world -> {
            int limit=config.worldLimit(world.getName());
            line(sender,"World "+world.getName()+": "+coordinator.occupancy(world)+" / "+(limit==Integer.MAX_VALUE?"unlimited":limit)+" (online + reserved)");
        });
        line(sender,"persistent-data="+config.isPersistData()+"; drop-inventory-on-quiting="+config.isDropInventoryOnQuiting()+
                "; keep-inventory-on-death="+config.isKeepInventoryOnDeath()+"; prevent-kicking="+config.getPreventKicking());
        if (!config.isPersistData() || config.isDropInventoryOnQuiting())
            sender.sendMessage(Component.translatable("fakeplayer.diagnose.persistence"));
        if (!residents.isReadable()) sender.sendMessage(Component.translatable("fakeplayer.resident.unreadable"));
        if (java.util.stream.Stream.of(config.getPreSpawnCommands(),config.getPostSpawnCommands(),config.getAfterSpawnCommands()).flatMap(List::stream).anyMatch(command->!command.isBlank()))
            line(sender,"Spawn hooks are configured: check their effects on position, inventory, and permissions.");
        line(sender,"Permissions: manage-others="+sender.hasPermission(Permission.manageOthers)+"; bypass-limits="+sender.hasPermission(Permission.bypassLimits));
        line(sender,"PRE_LOGIN listeners: "+DiagnosticManager.listeners(AsyncPlayerPreLoginEvent.getHandlerList()));
        line(sender,"LOGIN listeners: "+DiagnosticManager.listeners(PlayerLoginEvent.getHandlerList()));
        line(sender,"TELEPORT listeners: "+DiagnosticManager.listeners(PlayerTeleportEvent.getHandlerList()));
        line(sender,"JOIN listeners (inventory sync may run later): "+DiagnosticManager.listeners(PlayerJoinEvent.getHandlerList()));
        line(sender,"QUIT listeners: "+DiagnosticManager.listeners(PlayerQuitEvent.getHandlerList()));
        line(sender,"KICK listeners: "+DiagnosticManager.listeners(PlayerKickEvent.getHandlerList()));
        line(sender,"INVENTORY listeners: "+DiagnosticManager.listeners(InventoryOpenEvent.getHandlerList()));
        var hints=new LinkedHashMap<String,String>();
        hints.put("AuthMe","登录检查"); hints.put("AuthMeReloaded","登录检查"); hints.put("LoginSecurity","登录检查");
        hints.put("WorldGuard","区域交互/传送限制"); hints.put("GriefPrevention","领地交互限制");
        hints.put("Multiverse-Core","世界加载/传送"); hints.put("Multiverse-Inventories","背包切换");
        hints.put("HuskSync","背包/玩家数据同步"); hints.put("PlayerDataSQL","玩家数据同步");
        hints.put("MySQLPlayerDataBridge","玩家数据同步"); hints.put("OpenInv","背包查看");
        for (var hint:hints.entrySet()) if (Bukkit.getPluginManager().isPluginEnabled(hint.getKey()))
            line(sender,"排查线索: "+hint.getKey()+" 已启用（"+hint.getValue()+"），仅凭安装状态不能认定冲突。");
        line(sender,"CommandAPI: "+Optional.ofNullable(Bukkit.getPluginManager().getPlugin("CommandAPI")).map(p->p.getPluginMeta().getVersion()).orElse("missing"));
        sender.sendMessage(Component.translatable("fakeplayer.diagnose.leads"));
    }
    private static void line(CommandSender sender,String text) { sender.sendMessage(Component.text(text)); }
}
