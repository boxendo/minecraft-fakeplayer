package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.api.spi.*;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.entity.*;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager;
import io.github.hello09x.fakeplayer.core.manager.naming.SequenceName;
import io.github.hello09x.fakeplayer.core.util.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

@Singleton
public class ResidentManager {
    private static final Logger log=Main.getInstance().getLogger();
    private final FakeplayerList playerList;
    private final ActionManager actionManager;
    private final FakeplayerConfig config;
    private final SpawnCoordinator coordinator;
    private final DiagnosticManager diagnostics;
    private final File file=new File(Main.getInstance().getDataFolder(),"resident.yml");
    private final Map<UUID,Map<String,Object>> saved=new LinkedHashMap<>();
    private final Set<UUID> restoring=new HashSet<>();
    private final Map<UUID,Integer> attempts=new HashMap<>();
    private final Map<UUID,String> states=new HashMap<>(), reasons=new HashMap<>();
    private boolean readable=true, started, shuttingDown;
    private BukkitTask saveBatch;

    public record View(UUID uuid,String name,UUID creatorId,String creatorName,String world,
                       String state,String reason,int attempts,long lastSave,long lastRestore) {}

    @Inject
    public ResidentManager(FakeplayerList players, ActionManager actions, FakeplayerConfig config,
                           SpawnCoordinator coordinator, DiagnosticManager diagnostics) {
        this.playerList=players; this.actionManager=actions; this.config=config;
        this.coordinator=coordinator; this.diagnostics=diagnostics;
        load();
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), () -> {
            if (started && !shuttingDown) { restoreAll(); saveBatched(); }
        },200,200);
    }

    public boolean isReadable() { return readable; }
    public boolean isResident(Player player) {
        var fake=playerList.getByUUID(player.getUniqueId());
        return fake!=null && fake.isResident();
    }

    public void setResident(Player player,boolean resident) {
        if (!readable) throw new IllegalStateException("resident.yml 无法读取，已禁止覆盖；修复文件后重启");
        var fake=playerList.getByUUID(player.getUniqueId());
        if (fake==null) return;
        fake.setResident(resident);
        if (resident && player.isOnline()) { snapshot(fake); write(); }
        else if (!resident) removeEntry(player.getUniqueId());
    }

    public void forgetExplicit(UUID uuid) {
        if (!shuttingDown && !Bukkit.isStopping()) removeEntry(uuid);
    }

    public void forget(UUID uuid) {
        if (!shuttingDown && !Bukkit.isStopping() && !restoring.contains(uuid)) removeEntry(uuid);
    }

    public void onDisable() {
        shuttingDown=true;
        if (saveBatch!=null) { saveBatch.cancel(); saveBatch=null; }
        save();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            var yaml=new YamlConfiguration(); yaml.load(file);
            if (yaml.contains("players") && !yaml.isList("players")) throw new IllegalArgumentException("players must be a list");
            var names=new HashSet<String>();
            for (var raw:yaml.getList("players",List.of())) {
                if (!(raw instanceof Map<?,?> row)) throw new IllegalArgumentException("Resident entry must be a map");
                if (!(row.get("name") instanceof String nameValue) || nameValue.isBlank()) throw new IllegalArgumentException("Resident name is missing");
                var id=UUID.fromString(String.valueOf(row.get("uuid")));
                var name=String.valueOf(row.get("name"));
                if (saved.containsKey(id) || !names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate resident: "+name);
                var copy=new LinkedHashMap<String,Object>(); row.forEach((key,value)->copy.put(String.valueOf(key),value));
                saved.put(id,copy); states.put(id,"PENDING");
            }
        } catch (Exception error) {
            readable=false;
            log.log(java.util.logging.Level.SEVERE,"Cannot read resident.yml; writes disabled to protect existing data",error);
        }
    }

    public List<View> visible(CommandSender sender) {
        return saved.entrySet().stream().filter(e->mayManage(sender,e.getValue())).map(e->view(e.getKey(),e.getValue())).toList();
    }

    public View find(CommandSender sender,String key) {
        return visible(sender).stream().filter(v->v.name().equalsIgnoreCase(key)||v.uuid().toString().equalsIgnoreCase(key)).findFirst().orElse(null);
    }

    private boolean mayManage(CommandSender sender,Map<String,Object> row) {
        return FakeplayerAccess.mayManage(sender,parseUUID(Objects.toString(row.get("creator-uuid"),null)),Objects.toString(row.get("creator-name"),"CONSOLE"));
    }

    private View view(UUID id,Map<String,Object> row) {
        var fake=playerList.getByUUID(id);
        String state=fake!=null ? (fake.isOnline()?"ONLINE":"QUEUED") : states.getOrDefault(id,"PENDING");
        return new View(id,String.valueOf(row.get("name")),parseUUID(Objects.toString(row.get("creator-uuid"),null)),
                Objects.toString(row.get("creator-name"),"CONSOLE"),String.valueOf(row.get("world")),state,
                reasons.getOrDefault(id,Objects.toString(row.get("last-error"),"")),attempts.getOrDefault(id,0),
                number(row.get("last-saved-at")),number(row.get("last-restored-at")));
    }

    public String retry(UUID id) {
        if (!readable) return "resident.yml 无法读取，已禁止覆盖；修复文件后重启";
        var row=saved.get(id);
        if (row==null) return "没有此驻留记录";
        if (playerList.getByUUID(id)!=null) return "已在线或已在生成队列中";
        attempts.remove(id);
        var blocked=blocked(id,row);
        if (blocked!=null) return blocked;
        restore(id,row);
        return playerList.getByUUID(id)!=null?"已加入恢复队列":reasons.getOrDefault(id,"恢复未完成，请查看诊断");
    }

    public boolean forgetRecord(UUID id) {
        if (!readable || !saved.containsKey(id)) return false;
        removeEntry(id);
        var fake=playerList.getByUUID(id);
        if (fake!=null) {
            fake.setResident(false);
            if (restoring.contains(id) && !fake.isOnline()) { fake.cancelSpawn(); playerList.remove(fake); }
        }
        return true;
    }

    public void restoreAll() {
        started=true;
        if (!readable || shuttingDown) return;
        for (var e:new ArrayList<>(saved.entrySet())) {
            var id=e.getKey();
            if (playerList.getByUUID(id)!=null || restoring.contains(id)) continue;
            if (attempts.getOrDefault(id,0)>=3) {
                mark(id,"RETRY_EXHAUSTED","已达到本次启动的 3 次恢复尝试；可用 resident restore 手动重试"); continue;
            }
            if (blocked(id,e.getValue())==null) restore(id,e.getValue());
        }
    }

    private String blocked(UUID id,Map<String,Object> row) {
        String name=String.valueOf(row.get("name")), worldName=String.valueOf(row.get("world"));
        if (Bukkit.getWorld(worldName)==null) { mark(id,"WAITING_WORLD","世界尚未加载: "+worldName); return reasons.get(id); }
        if (Bukkit.getPlayerExact(name)!=null || Bukkit.getPlayer(id)!=null || playerList.getByName(name)!=null) {
            mark(id,"NAME_CONFLICT","名称或 UUID 已被在线玩家/生成请求占用；不会踢出占用者"); return reasons.get(id);
        }
        var world=Bukkit.getWorld(worldName);
        if (coordinator.occupancy(world)>=config.worldLimit(worldName)) {
            mark(id,"LIMIT_BLOCKED","世界假人上限已满: "+worldName); return reasons.get(id);
        }
        if (playerList.getSize()>=config.getServerLimit()) {
            mark(id,"LIMIT_BLOCKED","服务器假人上限已满"); return reasons.get(id);
        }
        if (coordinator.queued()>=config.getMaxQueuedSpawns()) {
            mark(id,"LIMIT_BLOCKED","生成队列已满，保留记录等待下一批"); return reasons.get(id);
        }
        return null;
    }

    private void mark(UUID id,String state,String reason) {
        if (state.equals(states.get(id)) && reason.equals(reasons.get(id))) return;
        states.put(id,state); reasons.put(id,reason);
        var row=saved.get(id);
        if (row!=null) diagnostics.resident(id,String.valueOf(row.get("name")),parseUUID(Objects.toString(row.get("creator-uuid"),null)),
                Objects.toString(row.get("creator-name"),"CONSOLE"),state,reason,number(row.get("last-saved-at")),number(row.get("last-restored-at")));
    }

    private void restore(UUID id,Map<String,Object> row) {
        Fakeplayer fake=null;
        try {
            var world=Objects.requireNonNull(Bukkit.getWorld(String.valueOf(row.get("world"))));
            var name=String.valueOf(row.get("name"));
            var ownerName=Objects.toString(row.get("creator-name"),"CONSOLE");
            var ownerId=parseUUID(Objects.toString(row.get("creator-uuid"),null));
            CommandSender owner=ownerId==null?null:Bukkit.getPlayer(ownerId);
            if (owner==null) owner=new OfflineCommandSender(ownerName,ownerId);
            var location=new Location(world,coordinate(row,"x"),coordinate(row,"y"),coordinate(row,"z"),
                    (float)coordinate(row,"yaw"),(float)coordinate(row,"pitch"));
            fake=new Fakeplayer(owner,Objects.toString(row.get("creator-ip"),"127.0.0.1"),new SequenceName("restored",0,id,name),0);
            fake.setResident(true); playerList.add(fake); restoring.add(id); attempts.merge(id,1,Integer::sum);
            var option=new SpawnOption(location,toBoolean(row,"invulnerable"),toBoolean(row,"collidable"),false,
                    toBoolean(row,"pickup-items"),false,toBoolean(row,"replenish"),toBoolean(row,"autofish"),toBoolean(row,"wolverine"),true);
            var actions=readActions(row); var target=fake;
            fake.spawnAsync(option,true).whenComplete((ignored,error)->ServerTasks.call(()->{
                if (error!=null) {
                    target.cancelSpawn();
                    if (target.isOnline()) target.getPlayer().kick(net.kyori.adventure.text.Component.text("[fakeplayer] restore failed"));
                    playerList.remove(target); restoring.remove(id);
                    if (saved.containsKey(id)) {
                        Throwable cause=error; while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
                        if (cause instanceof SpawnCoordinator.AdmissionException) {
                            attempts.computeIfPresent(id,(key,value)->Math.max(0,value-1));
                            mark(id,"LIMIT_BLOCKED",Objects.toString(cause.getMessage(),"生成预算暂不可用"));
                        } else {
                            mark(id,"FAILED",Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));
                            row.put("last-error",reasons.get(id));
                            log.warning("Failed to restore "+name+"; record retained: "+reasons.get(id));
                        }
                    }
                    return null;
                }
                restoring.remove(id);
                if (!saved.containsKey(id)) { target.setResident(false); return null; }
                var player=target.getPlayer();
                player.setSprinting(toBoolean(row,"sprinting")); player.setSneaking(toBoolean(row,"sneaking"));
                actions.forEach((type,setting)->actionManager.setAction(player,type,setting));
                row.put("last-restored-at",System.currentTimeMillis()); row.remove("last-error");
                diagnostics.restored(id); states.put(id,"ONLINE"); reasons.remove(id);
                saveBatched();
                log.info("Restored resident fake player '"+name+"' at "+player.getLocation());
                return null;
            }));
        } catch (Exception error) {
            if (fake!=null) { playerList.remove(fake); restoring.remove(id); }
            attempts.merge(id,1,Integer::sum); mark(id,"FAILED",Objects.toString(error.getMessage(),error.getClass().getSimpleName()));
            row.put("last-error",reasons.get(id));
            log.warning("Invalid resident '"+row.get("name")+"'; record retained: "+error);
        }
    }

    private static double coordinate(Map<String,Object> row,String key) {
        double value=Double.parseDouble(String.valueOf(row.get(key)));
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid coordinate "+key);
        return value;
    }
    private static long number(Object value) { return value instanceof Number n?n.longValue():0; }

    private void snapshot(Fakeplayer fake) {
        var player=fake.getPlayer();
        if (!fake.isResident() || !player.isOnline() || restoring.contains(fake.getUUID()) || playerList.getByUUID(fake.getUUID())!=fake) return;
        try {
            if (config.isPersistData()) { player.saveData(); diagnostics.saved(fake.getUUID()); }
            var row=toMap(fake,player); row.put("last-saved-at",System.currentTimeMillis());
            saved.put(fake.getUUID(),row);
        } catch (Exception error) {
            diagnostics.event(fake.getUUID(),"SAVE","保存失败: "+error,org.bukkit.event.player.PlayerQuitEvent.getHandlerList());
            log.warning("Failed to save "+fake.getName()+": "+error);
        }
    }

    public void save() {
        if (!readable) return;
        for (var fake:playerList.getAll()) snapshot(fake);
        write();
    }

    private void saveBatched() {
        if (!readable || shuttingDown || saveBatch!=null) return;
        var remaining=new ArrayDeque<>(playerList.getAll().stream().filter(Fakeplayer::isResident).toList());
        if (remaining.isEmpty()) { write(); return; }
        saveBatch=Bukkit.getScheduler().runTaskTimer(Main.getInstance(),()->{
            for (int i=0;i<config.getResidentSavesPerTick()&&!remaining.isEmpty();i++) snapshot(remaining.removeFirst());
            if (remaining.isEmpty()) { saveBatch.cancel(); saveBatch=null; write(); }
        },1,1);
    }

    private void write() {
        if (!readable || (saved.isEmpty()&&!file.exists())) return;
        var yaml=new YamlConfiguration(); yaml.set("format-version",2); yaml.set("players",new ArrayList<>(saved.values()));
        try { AtomicYamlFiles.write(yaml,file.toPath()); }
        catch (Exception error) { log.log(java.util.logging.Level.SEVERE,"Failed to save resident.yml",error); }
    }

    private void removeEntry(UUID uuid) {
        if (saved.remove(uuid)!=null) write();
        attempts.remove(uuid); states.remove(uuid); reasons.remove(uuid);
    }

    private @NotNull Map<String, Object> toMap(@NotNull Fakeplayer fake, @NotNull Player player) {
        var loc = player.getLocation();
        var map = new LinkedHashMap<String, Object>(saved.getOrDefault(fake.getUUID(), Map.of()));
        map.put("uuid", fake.getUUID().toString());
        map.put("name", fake.getName());
        var creatorUuid = fake.getCreator() instanceof Player p ? p.getUniqueId()
                : fake.getCreator() instanceof OfflineCommandSender offline ? offline.getUniqueId() : null;
        if (creatorUuid == null) {
            var legacyOwner = Bukkit.getPlayerExact(fake.getCreator().getName());
            if (legacyOwner != null && !Set.of("CONSOLE", "Rcon").contains(fake.getCreator().getName())) creatorUuid=legacyOwner.getUniqueId();
        }
        map.put("creator-uuid", creatorUuid == null ? null : creatorUuid.toString());
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
        map.put("wolverine", "true".equals(io.github.hello09x.fakeplayer.core.repository.model.Feature.wolverine.getDetector().apply(player)));
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
            if (setting.remains != 0) {
                result.put(type, setting);
            }
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
