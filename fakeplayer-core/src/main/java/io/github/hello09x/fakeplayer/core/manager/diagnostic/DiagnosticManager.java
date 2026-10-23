package io.github.hello09x.fakeplayer.core.manager.diagnostic;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.util.AtomicYamlFiles;
import io.github.hello09x.fakeplayer.core.util.FakeplayerAccess;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import java.io.File;
import java.time.Instant;
import java.util.*;

/** Bounded journal of observed outcomes. Listener lists are leads, never attribution. */
@Singleton
public class DiagnosticManager {
    public static final class Entry {
        public final UUID uuid;
        private java.lang.ref.WeakReference<Fakeplayer> source;
        public String name, creatorName, operation = "spawn", stage = "QUEUED", reason = "";
        public UUID creatorId;
        public long changedAt, lastAttempt, lastSave, lastRestore;
        public String failedStage = "", lastError = "";
        public final Deque<String> events = new ArrayDeque<>();

        private Entry(UUID uuid) { this.uuid = uuid; }
        private Entry copy() {
            var copy = new Entry(uuid);
            copy.name=name; copy.creatorName=creatorName; copy.creatorId=creatorId;
            copy.operation=operation; copy.stage=stage; copy.reason=reason; copy.changedAt=changedAt;
            copy.lastAttempt=lastAttempt; copy.lastSave=lastSave; copy.lastRestore=lastRestore;
            copy.failedStage=failedStage; copy.lastError=lastError; copy.events.addAll(events);
            return copy;
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final FakeplayerConfig config;
    private final File file = new File(Main.getInstance().getDataFolder(), "diagnostics.yml");
    private boolean readable = true, dirty;

    @Inject
    public DiagnosticManager(FakeplayerConfig config) {
        this.config = config;
        this.load();
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(), this::flush, 200, 200);
    }

    public synchronized void begin(Fakeplayer fake, boolean restoring) {
        var entry = ensure(fake.getUUID(), fake.getName(), FakeplayerAccess.creatorId(fake.getCreator()), fake.getCreator().getName());
        entry.source = new java.lang.ref.WeakReference<>(fake);
        entry.operation = restoring ? "restore" : "spawn";
        entry.lastAttempt = System.currentTimeMillis();
        stage(fake.getUUID(), "QUEUED", "等待生成预算");
    }

    public synchronized void resident(UUID uuid, String name, UUID creatorId, String creatorName,
                                      String stage, String reason, long lastSave, long lastRestore) {
        var entry = ensure(uuid, name, creatorId, creatorName);
        entry.source = null;
        entry.operation = "restore";
        entry.lastSave = Math.max(entry.lastSave, lastSave);
        entry.lastRestore = Math.max(entry.lastRestore, lastRestore);
        stage(uuid, stage, reason);
    }

    private Entry ensure(UUID uuid, String name, UUID ownerId, String ownerName) {
        var entry = entries.computeIfAbsent(uuid, Entry::new);
        entry.name=name; entry.creatorId=ownerId; entry.creatorName=ownerName;
        trim();
        return entry;
    }

    private boolean current(Fakeplayer fake) {
        var entry=entries.get(fake.getUUID());
        return entry!=null && entry.source!=null && entry.source.get()==fake;
    }

    public synchronized void stage(Fakeplayer fake,String stage,String reason) {
        if (current(fake)) stage(fake.getUUID(),stage,reason);
    }
    public synchronized void failure(Fakeplayer fake,Throwable error) {
        if (current(fake)) failure(fake.getUUID(),error);
    }
    public synchronized void event(Fakeplayer fake,String type,String outcome,HandlerList handlers) {
        if (current(fake)) event(fake.getUUID(),type,outcome,handlers);
    }

    public synchronized void stage(UUID uuid, String stage, String reason) {
        var entry = entries.get(uuid);
        if (entry == null) return;
        entry.stage=stage; entry.reason=clean(reason); entry.changedAt=System.currentTimeMillis(); dirty=true;
    }

    public synchronized void failure(UUID uuid, Throwable error) {
        var entry = entries.get(uuid);
        if (entry == null) return;
        while (error.getCause() != null && error.getCause() != error) error=error.getCause();
        entry.failedStage=entry.stage;
        entry.lastError=clean(error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(), ""));
        stage(uuid, "FAILED", entry.lastError);
    }

    public synchronized void offline(UUID uuid) {
        var entry=entries.get(uuid);
        if (entry != null && Set.of("ONLINE", "TELEPORT", "JOINING").contains(entry.stage)) stage(uuid,"OFFLINE","已退出");
    }

    public synchronized void saved(UUID uuid) {
        var entry=entries.get(uuid);
        if (entry != null) { entry.lastSave=System.currentTimeMillis(); dirty=true; }
    }

    public synchronized void restored(UUID uuid) {
        var entry=entries.get(uuid);
        if (entry != null) { entry.lastRestore=System.currentTimeMillis(); dirty=true; }
    }

    public synchronized void event(UUID uuid, String type, String outcome, HandlerList handlers) {
        var entry=entries.get(uuid);
        if (entry == null) return;
        entry.events.addLast(Instant.now()+" "+type+": "+clean(outcome)+"; 监听插件="+listeners(handlers));
        while (entry.events.size()>6) entry.events.removeFirst();
        dirty=true;
    }

    public static String listeners(HandlerList handlers) {
        return Arrays.stream(handlers.getRegisteredListeners())
                .map(listener -> listener.getPlugin().getName()+"/"+listener.getPriority())
                .distinct().limit(24).collect(java.util.stream.Collectors.joining(", "));
    }

    public synchronized Entry find(CommandSender sender, String key) {
        return entries.values().stream().filter(e -> e.name.equalsIgnoreCase(key) || e.uuid.toString().equalsIgnoreCase(key))
                .filter(e -> FakeplayerAccess.mayManage(sender,e.creatorId,e.creatorName)).findFirst().map(Entry::copy).orElse(null);
    }

    public synchronized List<Entry> visible(CommandSender sender) {
        return entries.values().stream().filter(e -> FakeplayerAccess.mayManage(sender,e.creatorId,e.creatorName))
                .map(Entry::copy).toList();
    }

    private void trim() {
        var active=Set.of("QUEUED","CHUNK_LOADING","PRE_LOGIN","PREPARING","LOAD_DATA","LOGIN","JOINING","TELEPORT","ONLINE");
        long history=entries.values().stream().filter(entry->!active.contains(entry.stage)).count();
        var iterator=entries.entrySet().iterator();
        while (history>config.getDiagnosticHistoryLimit() && iterator.hasNext()) {
            var entry=iterator.next().getValue();
            if (!active.contains(entry.stage)) { iterator.remove(); history--; }
        }
    }

    private static String clean(String text) {
        if (text == null) return "";
        text=text.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
        return text.length()>500 ? text.substring(0,500) : text;
    }

    public static String time(long value) { return value<=0 ? "尚无记录" : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(value)); }

    private void load() {
        if (!file.exists()) return;
        try {
            var yaml=new YamlConfiguration(); yaml.load(file);
            for (var row:yaml.getMapList("players")) {
                var id=UUID.fromString(String.valueOf(row.get("uuid")));
                var owner=row.get("creator-uuid");
                var e=ensure(id,String.valueOf(row.get("name")),owner==null?null:UUID.fromString(String.valueOf(owner)),String.valueOf(row.get("creator-name")));
                e.operation=Objects.toString(row.get("operation"),"spawn");
                // Queued jobs do not survive a process restart; resident restore will update them.
                e.stage="UNKNOWN"; e.reason="历史记录，当前进程尚未观察到生成";
                e.changedAt=number(row.get("changed-at")); e.lastAttempt=number(row.get("last-attempt"));
                e.lastSave=number(row.get("last-save")); e.lastRestore=number(row.get("last-restore"));
                e.failedStage=Objects.toString(row.get("failed-stage"),""); e.lastError=Objects.toString(row.get("last-error"),"");
                if (row.get("events") instanceof List<?> events) events.stream().limit(6).map(String::valueOf).forEach(e.events::addLast);
            }
        } catch (Exception error) {
            readable=false;
            Main.getInstance().getLogger().warning("Cannot read diagnostics.yml; journal writes disabled: "+error);
        }
    }

    private static long number(Object value) { return value instanceof Number n?n.longValue():0; }

    public synchronized void flush() {
        if (!readable || !dirty) return;
        trim();
        var rows=new ArrayList<Map<String,Object>>();
        for (var e:entries.values()) {
            var row=new LinkedHashMap<String,Object>();
            row.put("uuid",e.uuid.toString()); row.put("name",e.name); row.put("creator-name",e.creatorName);
            row.put("creator-uuid",e.creatorId==null?null:e.creatorId.toString()); row.put("operation",e.operation);
            row.put("changed-at",e.changedAt); row.put("last-attempt",e.lastAttempt); row.put("last-save",e.lastSave);
            row.put("last-restore",e.lastRestore); row.put("failed-stage",e.failedStage); row.put("last-error",e.lastError);
            row.put("events",new ArrayList<>(e.events)); rows.add(row);
        }
        var yaml=new YamlConfiguration(); yaml.set("players",rows);
        try { AtomicYamlFiles.write(yaml,file.toPath()); dirty=false; }
        catch (Exception error) { Main.getInstance().getLogger().warning("Failed to save diagnostics.yml: "+error); }
    }
}
