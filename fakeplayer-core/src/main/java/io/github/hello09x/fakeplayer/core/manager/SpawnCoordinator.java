package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.entity.SpawnOption;
import io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager;
import io.github.hello09x.fakeplayer.core.util.ServerTasks;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import java.util.*;
import java.util.concurrent.*;

/** One admission/queue/loading path for manual spawns and resident restores. Server-thread owned. */
@Singleton
public class SpawnCoordinator {
    public static final class AdmissionException extends IllegalStateException {
        public final String code;
        AdmissionException(String code, String message) { super(message); this.code=code; }
    }
    private record ChunkKey(UUID world, int x, int z) {}
    private static final class Job {
        final Fakeplayer fake;
        final SpawnOption option;
        final CompletableFuture<Void> result=new CompletableFuture<>();
        final ChunkKey chunk;
        final boolean restoring;
        boolean started, finished, leased;
        Job(Fakeplayer fake, SpawnOption option, boolean restoring) {
            this.fake=fake; this.option=option; this.restoring=restoring;
            chunk=new ChunkKey(option.spawnAt().getWorld().getUID(), option.spawnAt().getBlockX()>>4, option.spawnAt().getBlockZ()>>4);
        }
    }

    private final FakeplayerConfig config;
    private final FakeplayerList players;
    private final DiagnosticManager diagnostics;
    private final Deque<Job> queue=new ArrayDeque<>();
    private final Map<UUID,Job> jobs=new HashMap<>();
    private final Map<ChunkKey,CompletableFuture<Chunk>> loads=new HashMap<>();
    private final Map<ChunkKey,Integer> leases=new HashMap<>();
    private final Map<Integer,Integer> startsByTick=new LinkedHashMap<>(), loadsByTick=new LinkedHashMap<>();
    private int running;
    private boolean closed;

    @Inject
    public SpawnCoordinator(FakeplayerConfig config, FakeplayerList players, DiagnosticManager diagnostics) {
        this.config=config; this.players=players; this.diagnostics=diagnostics;
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(),this::tick,1,1);
    }

    public CompletableFuture<Void> submit(Fakeplayer fake, SpawnOption option, boolean restoring) {
        requireMain();
        diagnostics.begin(fake,restoring);
        String rejection=closed?"服务器正在关闭":queue.size()>=config.getMaxQueuedSpawns()?"生成队列已满":null;
        if (rejection!=null) return rejected(fake,"QUEUE_FULL",rejection);
        var world=option.spawnAt().getWorld();
        if (occupancy(world)>=config.worldLimit(world.getName())) {
            return rejected(fake,"WORLD_LIMIT","世界 "+world.getName()+" 已达到假人上限 "+config.worldLimit(world.getName()));
        }
        if (jobs.containsKey(fake.getUUID())) return rejected(fake,"DUPLICATE","此 UUID 已在生成队列中");
        var job=new Job(fake,option,restoring);
        jobs.put(fake.getUUID(),job); queue.addLast(job);
        return job.result;
    }

    private CompletableFuture<Void> rejected(Fakeplayer fake,String code,String reason) {
        var error=new AdmissionException(code,reason);
        diagnostics.failure(fake,error);
        return CompletableFuture.failedFuture(error);
    }

    public int occupancy(World world) {
        return (int)players.getAll().stream().filter(fake -> {
            var job=jobs.get(fake.getUUID());
            return job!=null ? job.chunk.world.equals(world.getUID())
                    : fake.isOnline() && fake.getPlayer().getWorld().equals(world);
        }).count();
    }

    public boolean mayEnter(Fakeplayer fake,World world) {
        var job=jobs.get(fake.getUUID());
        boolean counted=job!=null ? job.chunk.world.equals(world.getUID())
                : fake.isOnline() && fake.getPlayer().getWorld().equals(world);
        return occupancy(world)-(counted?1:0)<config.worldLimit(world.getName());
    }

    private void tick() {
        if (closed) return;
        int started=0, newLoads=0, scan=queue.size();
        while (scan-->0 && !queue.isEmpty() && started<config.getSpawnsPerTick() && running<config.getMaxConcurrentSpawns()) {
            var job=queue.removeFirst();
            if (job.finished) continue;
            if (job.fake.isSpawnCancelled() || players.getByUUID(job.fake.getUUID())!=job.fake) {
                finish(job,new CancellationException("生成已取消")); continue;
            }
            var world=job.option.spawnAt().getWorld();
            if (Bukkit.getWorld(world.getUID())==null) {
                finish(job,new IllegalStateException("目标世界已卸载")); continue;
            }
            if (occupancy(world)>config.worldLimit(world.getName())) {
                finish(job,new AdmissionException("WORLD_LIMIT","世界假人上限已降低")); continue;
            }
            boolean needsLoad=!world.isChunkLoaded(job.chunk.x,job.chunk.z) && !loads.containsKey(job.chunk);
            if (needsLoad && (newLoads>=config.getChunkLoadsPerTick() || loads.size()>=config.getMaxConcurrentChunkLoads())) {
                queue.addLast(job); continue;
            }
            started++;
            if (needsLoad) newLoads++;
            start(job);
        }
        record(startsByTick,started); record(loadsByTick,newLoads);
    }

    private static void record(Map<Integer,Integer> values,int count) {
        if (count>0) values.put(Bukkit.getCurrentTick(),count);
        while (values.size()>600) values.remove(values.keySet().iterator().next());
    }

    private void start(Job job) {
        job.started=true; running++;
        diagnostics.stage(job.fake,"CHUNK_LOADING","准备目标区块 "+job.chunk.x+","+job.chunk.z);
        CompletableFuture<Chunk> chunk;
        var world=job.option.spawnAt().getWorld();
        try {
            if (world.isChunkLoaded(job.chunk.x,job.chunk.z)) {
                chunk=CompletableFuture.completedFuture(world.getChunkAt(job.chunk.x,job.chunk.z));
            } else {
                chunk=loads.get(job.chunk);
                if (chunk==null) {
                    chunk=world.getChunkAtAsync(job.chunk.x,job.chunk.z,true);
                    loads.put(job.chunk,chunk);
                    var expected=chunk;
                    chunk.whenComplete((value,error) -> ServerTasks.call(() -> { loads.remove(job.chunk,expected); return null; }));
                }
            }
        } catch (Throwable error) { finish(job,error); return; }
        chunk.thenCompose(value -> ServerTasks.call(() -> {
            if (job.finished || job.fake.isSpawnCancelled()) throw new CancellationException("生成已取消");
            job.leased=true;
            if (leases.merge(job.chunk,1,Integer::sum)==1) {
                if (!world.addPluginChunkTicket(job.chunk.x,job.chunk.z,Main.getInstance())) {
                    // An existing ticket owned by this plugin is also valid.
                }
            }
            if (!job.restoring) {
                diagnostics.stage(job.fake,"PREPARING","运行生成前命令");
                Main.getInjector().getInstance(FakeplayerManager.class).dispatchCommandsEarly(job.fake,config.getPreSpawnCommands());
            }
            if (job.finished || job.fake.isSpawnCancelled()) throw new CancellationException("生成已取消");
            return job.fake.spawnNowAsync(job.option);
        })).thenCompose(future -> future).orTimeout(30,TimeUnit.SECONDS)
                .whenComplete((ignored,error) -> ServerTasks.call(() -> { finish(job,error); return null; }));
    }

    private void detach(Job job) {
        job.finished=true;
        jobs.remove(job.fake.getUUID(),job); queue.remove(job);
        if (job.started) running--;
        if (job.leased) {
            var count=leases.computeIfPresent(job.chunk,(key,value)->value-1);
            if (count!=null && count<=0) {
                leases.remove(job.chunk);
                try { job.option.spawnAt().getWorld().removePluginChunkTicket(job.chunk.x,job.chunk.z,Main.getInstance()); }
                catch (Exception error) { Main.getInstance().getLogger().warning("Could not release spawn chunk ticket: "+error); }
            }
            job.leased=false;
        }
    }

    private void finish(Job job,Throwable error) {
        if (job.finished) return;
        detach(job);
        if (error==null) {
            diagnostics.stage(job.fake,"ONLINE","生成完成");
            job.result.complete(null);
        } else {
            if (job.fake.isSpawnCancelled() || error instanceof CancellationException) diagnostics.stage(job.fake,"CANCELLED","生成已取消");
            else diagnostics.failure(job.fake,error);
            job.result.completeExceptionally(error);
        }
    }

    public void cancel(Fakeplayer fake) {
        requireMain();
        var job=jobs.get(fake.getUUID());
        if (job==null || job.fake!=fake) return;
        detach(job);
        diagnostics.stage(fake,"CANCELLED","生成已取消");
        Runnable complete=() -> job.result.completeExceptionally(new CancellationException("生成已取消"));
        complete.run();
    }

    public void close() {
        closed=true;
        for (var job:new ArrayList<>(jobs.values())) {
            job.fake.cancelSpawn();
        }
    }

    public int queued() { return queue.size(); }
    public int running() { return running; }
    public int loading() { return loads.size(); }
    public Map<Integer,Integer> startsByTick() { return Map.copyOf(startsByTick); }
    public Map<Integer,Integer> loadsByTick() { return Map.copyOf(loadsByTick); }
    public int leasedChunks() { return leases.size(); }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("SpawnCoordinator requires the server thread");
    }
}
