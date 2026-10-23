package verification;

import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.*;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.util.ServerTasks;
import io.github.hello09x.fakeplayer.api.spi.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only plugin. Never include in a production server or distribution. */
public final class VerificationPlugin extends JavaPlugin implements Listener {
    private FakeplayerManager manager;
    private final List<String> results = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger logins=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger peakLogins=new java.util.concurrent.atomic.AtomicInteger();

    public void onEnable() {
        manager = Main.getInjector().getInstance(FakeplayerManager.class);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (new java.io.File(getDataFolder(),"spawn-conflict.flag").exists()) {
            spawn("conflictbot").exceptionally(error -> { getLogger().warning("Conflict fixture spawn: "+error); return null; });
        }
    }

    @EventHandler public void prelogin(AsyncPlayerPreLoginEvent e) {
        if (e.getName().equals("retrybot") && new java.io.File(getDataFolder(),"deny-retry.flag").exists())
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,"fixture resident denial");
        if (e.getName().equals("denybot")) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "test denial");
        if (e.getName().startsWith("budget") || e.getName().startsWith("closetest")) {
            int active=logins.incrementAndGet(); peakLogins.accumulateAndGet(active,Math::max);
            try { Thread.sleep(250); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { logins.decrementAndGet(); }
        }
        if (e.getName().equals("slowbot")) {
            try { Thread.sleep(500); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
        }
    }

    @EventHandler
    public void cancelTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (event.getPlayer().getName().equals("tpdenybot")) event.setCancelled(true);
    }
    @EventHandler
    public void denyLogin(org.bukkit.event.player.PlayerLoginEvent event) {
        if (event.getPlayer().getName().equals("loginblocked")) event.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER,"fixture login denial");
    }
    @EventHandler
    public void cancelKick(org.bukkit.event.player.PlayerKickEvent event) {
        if (event.getPlayer().getName().equals("kickguardbot") && event.reason().toString().contains("fixture external")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (event.getPlayer().getName().equals("deathbot")) {
            getLogger().info("DEATH keepLevel=" + event.getKeepLevel() + " newLevel=" + event.getNewLevel() + " total=" + event.getNewTotalExp() + " health=" + event.getPlayer().getHealth());
        }
    }
    @EventHandler public void observeRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        getLogger().info("RESPAWN " + event.getPlayer().getName() + " XP=" + event.getPlayer().calculateTotalExperiencePoints());
    }
    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        results.add("PASS " + name);
        getLogger().info("PASS " + name);
    }
    private CompletableFuture<Void> later(long ticks, Runnable action) {
        var f = new CompletableFuture<Void>();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { action.run(); f.complete(null); } catch (Throwable e) { f.completeExceptionally(e); }
        }, ticks);
        return f;
    }
    private CompletableFuture<Player> spawn(String name) { return spawn(Bukkit.getConsoleSender(), name); }
    private CompletableFuture<Player> spawn(CommandSender owner, String name) {
        return manager.spawnAsync(owner, name, new Location(Bukkit.getWorlds().getFirst(), 10, -60, 10), -1);
    }
    private void equip(Player p) {
        p.getInventory().clear();
        p.getInventory().setItem(4, new ItemStack(Material.DIAMOND, 7));
        p.getInventory().setHeldItemSlot(4);
        p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        p.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        p.getEnderChest().setItem(0, new ItemStack(Material.EMERALD, 3));
        p.setLevel(0); p.setExp(0); p.setTotalExperience(0); p.giveExp(123, false);
        p.setFoodLevel(13); p.setSaturation(5); p.setHealth(14);
    }
    private void equipped(Player p) {
        check(p.getInventory().getItem(4) != null && p.getInventory().getItem(4).getAmount() == 7, "inventory survives");
        check(p.getInventory().getHeldItemSlot() == 4, "selected hotbar survives");
        check(p.getInventory().getItemInMainHand().getType() == Material.DIAMOND, "main hand survives");
        check(p.getInventory().getItemInOffHand().getType() == Material.SHIELD, "offhand survives");
        check(p.getInventory().getHelmet() != null, "armor survives");
        check(p.getEnderChest().getItem(0) != null && p.getEnderChest().getItem(0).getAmount() == 3, "ender chest survives");
        check(p.calculateTotalExperiencePoints() == 123, "XP survives: " + p.calculateTotalExperiencePoints());
    }
    private CompletableFuture<Void> state() {
        final UUID[] uuid = new UUID[1];
        return spawn("statebot").thenCompose(p -> {
            uuid[0] = p.getUniqueId(); equip(p); manager.remove(p.getName(), "test reconnect");
            check(manager.get("statebot") == null, "quit cleans registry");
            return spawn("statebot");
        }).thenCompose(p -> {
            check(uuid[0].equals(p.getUniqueId()), "same name same UUID"); equipped(p);
            check(p.getFoodLevel() == 13, "food survives");
            return spawn("recipient");
        }).thenAccept(recipient -> {
            recipient.setOp(true);
            int before = recipient.calculateTotalExperiencePoints();
            Bukkit.dispatchCommand(recipient, "fp expme statebot");
            check(manager.get("statebot").calculateTotalExperiencePoints() == 0, "expme clears source");
            check(recipient.calculateTotalExperiencePoints() == before + 123, "expme transfers full XP");
            Bukkit.dispatchCommand(recipient, "fp expme statebot");
            check(recipient.calculateTotalExperiencePoints() == before + 123, "expme cannot duplicate XP");
            recipient.setOp(false);
            manager.remove("recipient", "test done"); manager.remove("statebot", "test done");
        });
    }
    private CompletableFuture<Void> pending() {
        var future = spawn("slowbot");
        check(manager.isFake(manager.get("slowbot")), "pending login remains registered");
        try { spawn("SLOWBOT"); throw new AssertionError("duplicate accepted"); }
        catch (io.github.hello09x.devtools.command.exception.CommandException expected) { check(true, "pending duplicate rejected ignoring case"); }
        return future.thenCompose(p -> {
            check(p.isOnline(), "delayed login completes"); manager.remove("slowbot", "test done");
            return spawn("denybot").handle((value, error) -> ServerTasks.call(() -> {
                check(error != null, "denied login future fails");
                check(manager.get("denybot") == null, "denied login releases registry");
                return (Void) null;
            })).thenCompose(f -> f);
        });
    }
    private CompletableFuture<Void> resident() {
        return spawn("ownerbot").thenCompose(owner -> manager.spawnAsync(owner, "residentbot", new Location(Bukkit.getWorlds().getFirst(), 100, -60, 100), -1)).thenAccept(p -> {
            equip(p);
            p.setInvulnerable(true);
            Main.getInjector().getInstance(ResidentManager.class).setResident(p, true);
            Main.getInjector().getInstance(ActionManager.class).setAction(p, ActionType.JUMP, ActionSetting.interval(5));
            manager.remove("ownerbot", "creator offline");
            var wild = Main.getInjector().getInstance(WildFakeplayerManager.class);
            wild.cleanup0(Set.of()); wild.cleanup0(Set.of());
            check(p.isOnline(), "resident survives offline creator cleanup");
            check(Main.getInjector().getInstance(ActionManager.class).hasActiveAction(p, ActionType.JUMP), "continuous action reported active");
            Main.getInjector().getInstance(ResidentManager.class).save();
        });
    }
    private CompletableFuture<Void> restored() {
        var p = manager.get("residentbot");
        check(p != null && p.isOnline(), "resident restored online"); equipped(p);
        check(p.getLocation().distanceSquared(new Location(p.getWorld(), 100, -60, 100)) < 9, "resident position restored");
        check(Main.getInjector().getInstance(ActionManager.class).hasActiveAction(p, ActionType.JUMP), "resident action restored");
        check(Main.getInjector().getInstance(FakeplayerList.class).getByUUID(p.getUniqueId()).getCreator() instanceof io.github.hello09x.fakeplayer.core.util.OfflineCommandSender, "offline creator restored");
        return spawn("ownerbot").thenAccept(owner -> {
            check(Main.getInjector().getInstance(FakeplayerList.class).getByUUID(p.getUniqueId()).isCreatedBy(owner), "creator UUID owns restored fake player");
            manager.remove("ownerbot", "test done");
            Main.getInjector().getInstance(ResidentManager.class).save();
        });
    }
    private CompletableFuture<Void> actions() {
        return manager.spawnAsync(Bukkit.getConsoleSender(), "actionbot", new Location(Bukkit.getWorlds().getFirst(), 30, -60, 30), -1).thenCompose(p -> {
            p.setInvulnerable(true);
            var am = Main.getInjector().getInstance(ActionManager.class);
            double start = p.getY();
            am.setAction(p, ActionType.JUMP, ActionSetting.continuous());
            return later(4, () -> {
                check(p.getY() > start + 0.1, "jump changes actual position");
                am.stop(p);
            }).thenCompose(v -> later(30, () -> {
                var block = p.getWorld().getBlockAt(30, -59, 32);
                block.setType(Material.STONE);
                p.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));
                var look = p.getLocation();
                look.setDirection(block.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(p.getEyeLocation().toVector()));
                p.teleport(look);
                am.setAction(p, ActionType.MINE, ActionSetting.continuous());
            })).thenCompose(v -> later(80, () -> {
                check(p.getWorld().getBlockAt(30, -59, 32).getType() == Material.AIR, "mine breaks actual stone");
                am.stop(p);
                manager.remove("actionbot", "test done");
            }));
        });
    }
    private CompletableFuture<Void> death() {
        return spawn("deathbot").thenCompose(p -> {
            equip(p); p.setInvulnerable(false);
            p.getWorld().getEntities().stream().filter(e -> e instanceof Item || e instanceof ExperienceOrb).forEach(Entity::remove);
            p.setHealth(0);
            return later(5, () -> {
                check(!p.isOnline(), "kick-on-dead removes after death");
                var handle = ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle();
                getLogger().info("DEATH_AFTER XP=" + p.calculateTotalExperiencePoints() + " keep=" + handle.keepLevel + " newLevel=" + handle.newLevel + " health=" + p.getHealth());
                long drops = p.getWorld().getEntities().stream().filter(e -> e instanceof Item || e instanceof ExperienceOrb).count();
                boolean keep = Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.config.FakeplayerConfig.class).isKeepInventoryOnDeath();
                check(keep ? drops == 0 : drops > 0, "actual death drops match switch: " + drops);
                p.getWorld().getEntities().stream().filter(e -> e instanceof Item || e instanceof ExperienceOrb).forEach(Entity::remove);
            });
        }).thenCompose(v -> spawn("deathbot")).thenAccept(p -> {
            boolean keep = Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.config.FakeplayerConfig.class).isKeepInventoryOnDeath();
            if (keep) equipped(p);
            else {
                check(p.getInventory().getItem(4) == null, "vanilla death loses inventory");
                check(p.calculateTotalExperiencePoints() == 0, "vanilla death loses XP");
            }
            manager.remove("deathbot", "test done");
        });
    }
    private CompletableFuture<Void> cancel() {
        var f = spawn("slowbot");
        check(manager.remove("slowbot", "cancel pending"), "pending spawn can be removed");
        return f.handle((p, error) -> ServerTasks.call(() -> {
            check(error != null && Bukkit.getPlayerExact("slowbot") == null && manager.get("slowbot") == null, "cancelled spawn never joins");
            return (Void) null;
        })).thenCompose(x -> x);
    }
    private CompletableFuture<Void> respawn() {
        return spawn("respawnbot").thenCompose(p -> {
            equip(p); p.setHealth(0);
            return later(3, () -> {
                check(p.isOnline() && p.isDead(), "kick-on-dead false leaves dead fake online");
                Main.getInjector().getInstance(NMSBridge.class).fromPlayer(p).respawn();
                check(!p.isDead() && p.isOnline(), "explicit respawn revives fake");
                equipped(p);
                p.teleport(new Location(p.getWorld(), 20, -60, 20));
                p.setInvulnerable(true);
                Main.getInjector().getInstance(ActionManager.class).setAction(p, ActionType.JUMP, ActionSetting.continuous());
            }).thenCompose(v -> later(4, () -> {
                check(p.getY() > -59.9, "respawned fake still ticks and jumps");
                manager.remove("respawnbot", "test done");
            }));
        });
    }
    private CompletableFuture<Void> seed() {
        try {
            var file = new java.io.File(Main.getInstance().getDataFolder(), "resident.yml");
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            var list = new ArrayList<Map<?, ?>>(yaml.getMapList("players"));
            var record = new LinkedHashMap<String, Object>();
            for (var entry : list) if ("residentbot".equals(entry.get("name"))) entry.forEach((k,v) -> record.put(String.valueOf(k),v));
            check(!record.isEmpty(), "seed resident exists");
            record.put("name", "parkedbot"); record.put("uuid", UUID.randomUUID().toString()); record.put("world", "late_world");
            list.add(record); yaml.set("players", list); yaml.save(file);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }
    private CompletableFuture<Void> unavailable() {
        var file = new java.io.File(Main.getInstance().getDataFolder(), "resident.yml");
        Main.getInjector().getInstance(ResidentManager.class).save();
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        check(yaml.getMapList("players").stream().anyMatch(e -> "parkedbot".equals(e.get("name"))), "unavailable world record survives autosave");
        new WorldCreator("late_world").type(WorldType.FLAT).generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\"}").createWorld();
        Main.getInjector().getInstance(ResidentManager.class).restoreAll();
        return later(40, () -> {
            check(manager.get("parkedbot") != null && manager.get("parkedbot").isOnline(), "resident retries after world loads");
            Main.getInjector().getInstance(ResidentManager.class).setResident(manager.get("parkedbot"), false);
            manager.remove("parkedbot", "test done");
        });
    }
    private static final class Capture extends io.github.hello09x.fakeplayer.v26_3.network.FakeServerGamePacketListenerImpl {
        final List<String> messages=new ArrayList<>();
        Capture(Player player) {
            super(((org.bukkit.craftbukkit.CraftServer)Bukkit.getServer()).getServer(),
                    ((org.bukkit.craftbukkit.entity.CraftPlayer)player).getHandle().connection.connection,
                    ((org.bukkit.craftbukkit.entity.CraftPlayer)player).getHandle(),
                    net.minecraft.server.network.CommonListenerCookie.createInitial(((org.bukkit.craftbukkit.entity.CraftPlayer)player).getProfile(),false));
            ((org.bukkit.craftbukkit.entity.CraftPlayer)player).getHandle().connection=this;
        }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
            if (messages!=null && packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) messages.add(chat.content().getString());
            super.send(packet);
        }
        String output() { return String.join("\n",messages); }
    }

    private CompletableFuture<Void> withConfig(Map<String,Object> changes,java.util.function.Supplier<CompletableFuture<Void>> action) {
        var path=Main.getInstance().getDataFolder().toPath().resolve("config.yml");
        try {
            String before=Files.readString(path);
            var yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(path.toFile());
            changes.forEach(yaml::set); yaml.save(path.toFile());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"fp reload");
            CompletableFuture<Void> test;
            try { test=action.get(); } catch(Throwable error) { test=CompletableFuture.failedFuture(error); }
            return test.handle((v,error)->ServerTasks.call(()->{
                try { Files.writeString(path,before); } catch(Exception write) { throw new RuntimeException(write); }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"fp reload");
                if (error!=null) throw new CompletionException(error);
                return (Void)null;
            })).thenCompose(v->v);
        } catch(Exception error) { return CompletableFuture.failedFuture(error); }
    }

    private CompletableFuture<Void> management() {
        var rm=Main.getInjector().getInstance(ResidentManager.class);
        var dm=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class);
        final Player[] actors=new Player[4];
        return spawn("mgrowner").thenCompose(owner->{actors[0]=owner; return spawn("mgrother");})
                .thenCompose(other->{actors[1]=other; return spawn(actors[0],"mgrchild");})
                .thenCompose(child->{actors[2]=child; return spawn(actors[1],"othermanaged");})
                .thenAccept(otherChild->{
            actors[3]=otherChild;
            Player owner=actors[0], other=actors[1], child=actors[2];
            owner.setOp(false); other.setOp(false);
            var permissions=owner.addAttachment(this);
            var otherPermissions=other.addAttachment(this);
            for(var node:List.of("fakeplayer.command.resident","fakeplayer.command.resident.restore","fakeplayer.command.resident.forget","fakeplayer.command.diagnose")) {
                permissions.setPermission(node,true); otherPermissions.setPermission(node,true);
            }
            permissions.setPermission("fakeplayer.command.spawn",false);
            var capture=new Capture(owner);
            rm.setResident(child,true); rm.setResident(otherChild,true);
            check(rm.find(owner,"mgrchild")!=null && rm.find(other,"mgrchild")==null,"resident ownership filters reads");
            check(dm.find(owner,"mgrchild")!=null && dm.find(other,"mgrchild")==null,"diagnostic ownership filters reads");
            capture.messages.clear(); Bukkit.dispatchCommand(owner,"fp resident list");
            check(capture.output().contains("mgrchild")&&!capture.output().contains("othermanaged"),"resident list works without spawn permission and hides others");
            capture.messages.clear(); Bukkit.dispatchCommand(owner,"fp diagnose mgrchild");
            check(capture.output().contains("mgrchild"),"personal diagnose command executes without spawn permission");
            dm.stage(otherChild.getUniqueId(),"ONLINE","SECRET-FOREIGN");
            capture.messages.clear(); Bukkit.dispatchCommand(owner,"fp diagnose othermanaged");
            check(!capture.output().contains("SECRET-FOREIGN"),"diagnose does not expose foreign details");
            Bukkit.dispatchCommand(owner,"fp resident forget othermanaged");
            check(rm.find(Bukkit.getConsoleSender(),"othermanaged")!=null,"foreign resident removal denied");
            Bukkit.dispatchCommand(owner,"fp resident false unknownrecord");
            check(rm.isResident(child),"invalid explicit target does not fall back to own fake");
            manager.setSelection(owner,otherChild);
            Bukkit.dispatchCommand(owner,"fp resident false");
            check(rm.isResident(otherChild),"revoked administrator cannot reuse foreign selection");
            var inv=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.invsee.InvseeManager.class);
            permissions.setPermission("fakeplayer.command.invsee",false);
            check(!inv.invsee(owner,child),"inventory API respects invsee permission");
            permissions.setPermission("fakeplayer.command.invsee",true);
            check(!inv.invsee(owner,otherChild),"inventory API respects ownership");
            permissions.setPermission("fakeplayer.admin.manage-others",true);
            check(!owner.isOp() && rm.find(owner,"othermanaged")!=null,"delegated administrator needs no OP");
            Bukkit.dispatchCommand(owner,"fp resident false");
            check(!rm.isResident(otherChild),"delegated administrator can control foreign selection");
            rm.setResident(otherChild,true);
            capture.messages.clear(); Bukkit.dispatchCommand(owner,"fp diagnose");
            check(!capture.output().contains("CommandAPI:"),"manage-others does not grant server diagnostics");
            permissions.setPermission("fakeplayer.admin.diagnostics",true);
            capture.messages.clear(); Bukkit.dispatchCommand(owner,"fp diagnose");
            check(capture.output().contains("CommandAPI:"),"server diagnostics permission works without OP");
            try { spawn(owner,"quotaextra"); throw new AssertionError("quota bypassed"); }
            catch(io.github.hello09x.devtools.command.exception.CommandException expected) { check(true,"manage-others does not bypass creator quota"); }
            Player renamed=(Player)java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(proxy,method,args)->{
                if(method.getName().equals("getName")) return "renamedowner";
                return method.invoke(owner,args);
            });
            check(manager.getAll(renamed).contains(child)&&rm.find(renamed,"mgrchild")!=null,"creator rename retains UUID ownership");
            Player impersonator=(Player)java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(proxy,method,args)->{
                if(method.getName().equals("getName")) return owner.getName();
                return method.invoke(other,args);
            });
            check(rm.find(impersonator,"mgrchild")==null,"same name with a different UUID cannot claim owner record");
            permissions.setPermission("fakeplayer.admin.manage-others",false);
            Bukkit.dispatchCommand(owner,"fp resident forget mgrchild");
            check(rm.find(owner,"mgrchild")==null&&child.isOnline(),"forget removes residency and leaves online fake alive");
            check(new java.io.File(Main.getInjector().getInstance(NMSBridge.class).fromServer(Bukkit.getServer()).getPlayerDataFolder(),child.getUniqueId()+".dat").isFile(),"forget retains native playerdata");
            for(var actor:actors) manager.remove(actor.getName(),"management test done");
        });
    }

    private CompletableFuture<Void> conflicts() {
        var dm=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class);
        return spawn("tpdenybot").handle((p,error)->ServerTasks.call(()->{
            check(error!=null&&manager.get("tpdenybot")==null,"cancelled placement fails and cleans registry");
            var e=dm.find(Bukkit.getConsoleSender(),"tpdenybot");
            check(e!=null&&e.failedStage.equals("TELEPORT"),"diagnostics identify actual failed teleport stage");
            check(e.events.stream().anyMatch(text->text.contains("cancelled=true")&&text.contains("FakeplayerVerification")),"diagnostics preserve cancellation result and listener leads");
            return (Void)null;
        })).thenCompose(f->f).thenCompose(v->withConfig(Map.of("prevent-kicking","NEVER"),()->
                spawn("loginblocked").handle((p,error)->ServerTasks.call(()->{
                    check(error!=null,"login denial enforced under NEVER policy");
                    var e=dm.find(Bukkit.getConsoleSender(),"loginblocked");
                    check(e!=null&&e.failedStage.equals("LOGIN")&&e.events.stream().anyMatch(text->text.contains("fixture login denial")),"diagnostics preserve login denial reason");
                    return (Void)null;
                })).thenCompose(f->f))).thenCompose(v->spawn("kickguardbot")).thenCompose(p->{
            p.kick(net.kyori.adventure.text.Component.text("fixture external"));
            check(p.isOnline(),"fixture kick cancellation keeps fake online");
            var e=dm.find(Bukkit.getConsoleSender(),"kickguardbot");
            check(e.events.stream().anyMatch(text->text.contains("KICK")&&text.contains("cancelled=true")),"diagnostics preserve cancelled kick outcome");
            manager.remove(p.getName(),"test done"); dm.flush();
            long expectedAttempt=dm.find(Bukkit.getConsoleSender(),"tpdenybot").lastAttempt;
            return awaitDisk(()->{
                var yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(Main.getInstance().getDataFolder(),"diagnostics.yml"));
                return yaml.getMapList("players").stream().anyMatch(row->"tpdenybot".equals(row.get("name"))&&"TELEPORT".equals(row.get("failed-stage"))&&Long.valueOf(expectedAttempt).equals(row.get("last-attempt")));
            }).thenRun(()->check(true,"fresh diagnostic history asynchronously persists to disk"));
        });
    }

    private CompletableFuture<Void> budgets() {
        var c=Main.getInjector().getInstance(SpawnCoordinator.class);
        var world=Bukkit.getWorlds().getFirst();
        return withConfig(Map.of("spawning.per-tick",2,"spawning.max-concurrent",2,"spawning.chunk-loads-per-tick",1,
                "spawning.max-concurrent-chunk-loads",1,"spawning.max-queued",32),()->{
            peakLogins.set(0); int start=Bukkit.getCurrentTick();
            List<CompletableFuture<Player>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(manager.spawnAsync(Bukkit.getConsoleSender(),"budget"+i,new Location(world,3000+i*64,-60,3000),-1));
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenCompose(v->later(2,()->{
                var starts=c.startsByTick().entrySet().stream().filter(e->e.getKey()>=start).toList();
                check(starts.stream().mapToInt(Map.Entry::getValue).sum()==8,"all queued budget jobs started exactly once");
                check(starts.stream().allMatch(e->e.getValue()<=2)&&starts.size()>=4,"per-tick spawn budget enforced");
                var loads=c.loadsByTick().entrySet().stream().filter(e->e.getKey()>=start).toList();
                check(!loads.isEmpty()&&loads.stream().allMatch(e->e.getValue()<=1),"new target chunk request budget enforced");
                check(peakLogins.get()>0&&peakLogins.get()<=2,"concurrent prelogin count bounded");
                for(var future:futures) manager.remove(future.join().getName(),"budget done");
                check(c.queued()==0&&c.running()==0&&c.loading()==0&&c.leasedChunks()==0,"completed queue releases jobs, loads and tickets");
            }));
        }).thenCompose(v->withConfig(Map.of("spawning.max-queued",2,"spawning.max-concurrent",1,
                "pre-spawn-commands",List.of("scoreboard players add %p fphooks 1")),()->{
            var scoreboard=Bukkit.getScoreboardManager().getMainScoreboard();
            var old=scoreboard.getObjective("fphooks"); if(old!=null) old.unregister();
            var hooks=scoreboard.registerNewObjective("fphooks",org.bukkit.scoreboard.Criteria.DUMMY,net.kyori.adventure.text.Component.text("hooks"));
            var one=spawn("budgetqueue1"); var two=spawn("budgetqueue2"); var third=spawn("budgetqueue3");
            check(third.isCompletedExceptionally()&&manager.get("budgetqueue3")==null,"full queue rejects and releases registry");
            check(!hooks.getScore("budgetqueue3").isScoreSet(),"rejected queue job does not run pre-spawn hooks");
            var cancelled=spawn("budgetcancel");
            check(cancelled.isCompletedExceptionally(),"queue stays full until jobs start");
            return CompletableFuture.allOf(one,two).thenRun(()->{
                check(hooks.getScore("budgetqueue1").getScore()==1&&hooks.getScore("budgetqueue2").getScore()==1,"admitted jobs run pre-spawn hooks once");
                hooks.unregister();
                manager.remove("budgetqueue1","done"); manager.remove("budgetqueue2","done");
            });
        })).thenCompose(v->withConfig(Map.of("spawning.max-players-per-world",c.occupancy(world)+2),()->{
            var one=spawn("worldcap1"); var two=spawn("worldcap2"); var third=spawn("worldcap3");
            check(third.isCompletedExceptionally()&&manager.get("worldcap3")==null,"world cap reserves queued jobs and blocks third player");
            return CompletableFuture.allOf(one,two).thenCompose(completed->{
                check(c.occupancy(world)<=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.config.FakeplayerConfig.class).worldLimit(world.getName()),"world cap enforced when jobs are online");
                one.join().setInvulnerable(true);
                var otherWorld=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NETHER).findFirst().orElseThrow();
                return one.join().teleportAsync(new Location(otherWorld,0,64,0));
            }).thenCompose(moved->{
                check(moved,"fake can leave capped world");
                return spawn("worldcap3");
            }).thenCompose(replacement->one.join().teleportAsync(new Location(world,10,-60,10)))
                    .thenAccept(moved->{
                check(!moved&&!one.join().getWorld().equals(world),"cross-world teleport cannot bypass a full world cap");
                manager.remove("worldcap1","done"); manager.remove("worldcap2","done"); manager.remove("worldcap3","done");
            });
        }));
    }

    private CompletableFuture<Void> skinConfigured() {
        return withConfig(Map.of("default-features.wolverine",true,"default-online-skin",true),()->{
            long start=System.nanoTime();
            return spawn("netprobe").thenAccept(player->{
                check((System.nanoTime()-start)/1_000_000<5000,"online skin lookup does not block spawn completion");
                var effect=player.getPotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION);
                check(effect!=null&&effect.isInfinite(),"default regeneration applies after connection setup");
                manager.remove("netprobe","skin done");
            });
        });
    }

    private CompletableFuture<Void> race() {
        var first=spawn("slowbot");
        final CompletableFuture<Player>[] second=new CompletableFuture[1];
        return later(3,()->{
            manager.remove("slowbot","replace pending");
            second[0]=spawn("slowbot");
        }).thenCompose(v->second[0]).thenAccept(player->{
            check(first.isCompletedExceptionally()&&player.isOnline()&&manager.get("slowbot")==player,"replacing cancelled login keeps new registration intact");
            var e=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class).find(Bukkit.getConsoleSender(),"slowbot");
            long outcomes=e.events.stream().filter(text->text.contains("PRE_LOGIN")&&java.time.Instant.parse(text.split(" ")[0]).toEpochMilli()>=e.lastAttempt).count();
            check(e.stage.equals("ONLINE")&&outcomes==1,"late old login cannot overwrite current operation diagnostics");
            manager.remove("slowbot","race done");
        });
    }

    private CompletableFuture<Void> offlineManagement() {
        var rm=Main.getInjector().getInstance(ResidentManager.class);
        var console=Bukkit.getConsoleSender();
        var actor=manager.get("conflictbot");
        check(actor!=null,"conflict fixture occupies name with different UUID");
        check(rm.find(console,"conflictbot").state().equals("NAME_CONFLICT"),"saved record reports name conflict");
        var occupied=actor.getUniqueId();
        Bukkit.dispatchCommand(console,"fp resident restore conflictbot");
        check(actor.isOnline()&&manager.get("conflictbot").getUniqueId().equals(occupied),"manual restore cannot kick occupying player");
        manager.remove("conflictbot","clear name conflict");
        Bukkit.dispatchCommand(console,"fp resident restore conflictbot");
        return later(15,()->{
            check(manager.get("conflictbot")!=null&&manager.get("conflictbot").isOnline(),"manual retry restores after name becomes free");
            Bukkit.dispatchCommand(console,"fp resident forget conflictbot");
            manager.remove("conflictbot","done");
            new java.io.File(getDataFolder(),"spawn-conflict.flag").delete();
            var retry=rm.find(console,"retrybot");
            check(retry!=null&&retry.attempts()==1&&retry.state().equals("FAILED"),"failed resident remains with first attempt recorded");
            rm.restoreAll();
        }).thenCompose(v->later(10,()->{check(rm.find(console,"retrybot").attempts()==2,"automatic retry increments real failure attempt");rm.restoreAll();}))
                .thenCompose(v->later(10,()->{
            rm.restoreAll();
            check(rm.find(console,"retrybot").state().equals("RETRY_EXHAUSTED"),"three real failures require manual retry without deleting record");
            new java.io.File(getDataFolder(),"deny-retry.flag").delete();
            Bukkit.dispatchCommand(console,"fp resident restore retrybot");
        })).thenCompose(v->later(15,()->{
            check(manager.get("retrybot")!=null&&manager.get("retrybot").isOnline(),"manual retry resets exhausted attempts after conflict is resolved");
            Bukkit.dispatchCommand(console,"fp resident forget retrybot");manager.remove("retrybot","done");
            var parked=rm.find(console,"parkedbot");
            check(parked!=null&&parked.state().equals("WAITING_WORLD")&&parked.attempts()==0,"unloaded world preserves record without consuming attempts");
            Bukkit.dispatchCommand(console,"fp resident restore parkedbot");
            check(rm.find(console,"parkedbot").attempts()==0,"manual retry reports unavailable world without consuming attempts");
        })).thenCompose(v->unavailable());
    }

    private CompletableFuture<Void> journalRestored() {
        var dm=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class);
        var entry=dm.find(Bukkit.getConsoleSender(),"tpdenybot");
        check(entry!=null&&entry.failedStage.equals("TELEPORT")&&entry.events.stream().anyMatch(text->text.contains("cancelled=true")),"failure stage and event evidence survive process restart");
        check(entry.stage.equals("UNKNOWN"),"historical diagnostics do not claim a new live operation");
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> closing() {
        var c=Main.getInjector().getInstance(SpawnCoordinator.class);
        List<CompletableFuture<Player>> futures=new ArrayList<>();
        for(int i=0;i<3;i++) futures.add(spawn("closetest"+i));
        return later(1,()->{
            c.close();
            check(futures.stream().allMatch(CompletableFuture::isCompletedExceptionally),"coordinator close completes all cancelled futures");
            check(c.queued()==0&&c.running()==0&&c.leasedChunks()==0,"coordinator close releases queue and tickets");
        }).thenCompose(v->later(20,()->{
            check(manager.getAll().stream().noneMatch(p->p.getName().startsWith("closetest")),"cancelled shutdown jobs cannot join later");
        }));
    }

    private CompletableFuture<Void> awaitDisk(java.util.function.BooleanSupplier condition) {
        var future=new CompletableFuture<Void>(); int[] attempts={0};
        var task=Bukkit.getScheduler().runTaskTimer(this,()->{
            try {
                if(condition.getAsBoolean()) future.complete(null);
                else if(++attempts[0]>=100) future.completeExceptionally(new AssertionError("fresh disk snapshot not available within 10 seconds"));
            } catch(Throwable e) { future.completeExceptionally(e); }
        },2,2);
        future.whenComplete((v,e)->task.cancel()); return future;
    }

    private CompletableFuture<Void> residentToggleIntegrity() {
        return spawn("Toggle51").thenCompose(p->{
            var rm=Main.getInjector().getInstance(ResidentManager.class);
            rm.setResident(p,true); rm.setResident(p,false); rm.save();
            return awaitDisk(()->{
                var yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(Main.getInstance().getDataFolder(),"resident.yml"));
                return yaml.getMapList("players").stream().noneMatch(row->"Toggle51".equals(row.get("name")));
            }).thenRun(()->{check(rm.find(Bukkit.getConsoleSender(),"Toggle51")==null,"rapid resident enable/disable leaves no saved record");manager.remove(p.getName(),"done");});
        });
    }

    private CompletableFuture<Void> caseIntegrity() {
        var repo=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.repository.FakeplayerProfileRepository.class);
        // Seed these two rows offline before startup to exercise non-destructive migration.
        var oldA=repo.selectUUIDByName("LegacyCase51"); var oldB=repo.selectUUIDByName("legacycase51");
        check(oldA!=null && oldB!=null && !oldA.equals(oldB),"legacy case conflicts keep independent UUIDs");
        try { repo.selectUUIDByName("LEGACYCASE51"); throw new AssertionError("ambiguous legacy name accepted"); }
        catch (io.github.hello09x.devtools.command.exception.CommandException expected) { check(true,"ambiguous legacy spelling rejected without merging saves"); }
        UUID[] id=new UUID[1];
        return spawn("CaseFresh51").thenCompose(p->{
            id[0]=p.getUniqueId(); equip(p); manager.remove(p.getName(),"case reconnect");
            return spawn("casefresh51");
        }).thenCompose(p->{
            check(id[0].equals(p.getUniqueId()),"lowercase reconnect reuses UUID"); equipped(p);
            manager.remove(p.getName(),"case reconnect"); return spawn("CASEFRESH51");
        }).thenAccept(p->{
            check(id[0].equals(p.getUniqueId()),"uppercase reconnect reuses UUID"); equipped(p);
            manager.remove(p.getName(),"done");
            try { repo.insert("cASEfRESH51",UUID.randomUUID()); throw new AssertionError("duplicate inserted"); }
            catch (RuntimeException expected) { check(true,"database rejects new case duplicate"); }
        });
    }

    private CompletableFuture<Void> nearestIntegrity() {
        return spawn("Nearest51").thenAccept(p->{
            var loc=p.getLocation(); var far=p.getWorld().spawn(loc.clone().add(4,0,0),ArmorStand.class);
            var near=p.getWorld().spawn(loc.clone().add(1,0,0),ArmorStand.class);
            var gone=p.getWorld().spawn(loc.clone().add(.1,0,0),ArmorStand.class); gone.remove();
            try {
                var method=io.github.hello09x.fakeplayer.core.entity.action.impl.LookAtEntityAction.class.getDeclaredMethod("getNearestEntity",Player.class,Collection.class);
                method.setAccessible(true); var action=new io.github.hello09x.fakeplayer.core.entity.action.impl.LookAtEntityAction(null);
                check(method.invoke(action,p,List.of(far,near))==near,"nearest selected even when farther entity appears first");
                check(method.invoke(action,p,List.of(near,far))==near,"nearest independent of candidate order");
                check(method.invoke(action,p,List.of(p,gone,far,near))==near,"nearest excludes self and removed entities");
                check(method.invoke(action,p,List.of())==null,"nearest empty candidates safe");
            } catch(ReflectiveOperationException error) { throw new RuntimeException(error); }
            finally { far.remove(); near.remove(); manager.remove(p.getName(),"done"); }
        });
    }

    private String refillEventMode="";
    @EventHandler public void refillEvent(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!event.getWhoClicked().getName().equals("Refill51")) return;
        switch(refillEventMode) {
            case "cancel-event" -> event.setCancelled(true);
            case "hand-event" -> ((Player)event.getWhoClicked()).getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            case "source-event" -> event.getInventory().setItem(event.getSlot(),new ItemStack(Material.DIRT,3));
        }
    }

    private CompletableFuture<Void> refillIntegrity() {
        return manager.spawnAsync(Bukkit.getConsoleSender(),"Refill51",new Location(Bukkit.getWorlds().getFirst(),650,-60,650),-1).thenCompose(p->{
            p.setGravity(false); p.setInvulnerable(true); p.setCanPickupItems(false);
            var block=p.getLocation().clone().add(2,0,0).getBlock(); var original=block.getState(); block.setType(Material.CHEST);
            var inv=((org.bukkit.block.Chest)block.getState()).getBlockInventory();
            var rm=Main.getInjector().getInstance(FakeplayerReplenishManager.class);
            CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
            for(var mode:List.of("normal","hand-change","slot-change","window-change","disabled","cancel-event","hand-event","source-event","quit")) {
                chain=chain.thenCompose(v->{
                    p.closeInventory(); p.getInventory().clear(); p.getInventory().setHeldItemSlot(0);
                    inv.clear(); inv.setItem(0,new ItemStack(Material.STONE,64)); inv.setItem(1,new ItemStack(Material.STONE,32));
                    rm.setReplenish(p,true); refillEventMode=mode;
                    rm.replenishFromNearbyChest(p,org.bukkit.inventory.EquipmentSlot.HAND,new ItemStack(Material.STONE));
                    var view=p.getOpenInventory();
                    check(view.getTopInventory().equals(inv),"refill fixture opens intended chest: "+mode);
                    rm.replenishFromNearbyChest(p,org.bukkit.inventory.EquipmentSlot.OFF_HAND,new ItemStack(Material.STONE));
                    check(p.getOpenInventory()==view,"duplicate refill does not replace window: "+mode);
                    return later(5,()->{
                        switch(mode) {
                            case "hand-change" -> p.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                            case "slot-change" -> p.getInventory().setHeldItemSlot(2);
                            case "window-change" -> p.openInventory(Bukkit.createInventory(null,27));
                            case "disabled" -> rm.setReplenish(p,false);
                            case "quit" -> manager.remove(p.getName(),"pending refill quit");
                        }
                    }).thenCompose(v2->later(22,()->{
                        var hand=p.getInventory().getItemInMainHand();
                        if(mode.equals("normal")) {
                            check(hand.getType()==Material.STONE && hand.getAmount()==32 && inv.getItem(1)==null && inv.getItem(0).getAmount()==64,"normal refill moves exactly one source stack");
                        } else if(mode.equals("hand-change") || mode.equals("hand-event")) {
                            check(hand.getType()==Material.DIAMOND_SWORD && inv.getItem(1).getAmount()==32,"replacement hand protected: "+mode);
                        } else if(mode.equals("source-event")) {
                            check(hand.isEmpty() && inv.getItem(1).getType()==Material.DIRT && inv.getItem(1).getAmount()==3,"event-modified source is not copied or deleted");
                        } else {
                            check(hand.isEmpty() && inv.getItem(1).getAmount()==32,"cancelled/stale refill leaves items intact: "+mode);
                            if(mode.equals("window-change")) check(p.getOpenInventory().getTopInventory().getType()==org.bukkit.event.inventory.InventoryType.CHEST && !p.getOpenInventory().getTopInventory().equals(inv),"replacement window remains open");
                        }
                        check(p.getInventory().getItemInOffHand().isEmpty(),"duplicate refill cannot also fill other hand: "+mode);
                    }));
                });
            }
            return chain.whenComplete((v,error)->{refillEventMode=""; rm.setReplenish(p,false); manager.remove(p.getName(),"done");original.update(true,false);});
        });
    }

    private static final class FaultTicker implements ActionTicker {
        private final ActionSetting setting=ActionSetting.interval(2);
        int attempts,stops,ticks; boolean failing=true;
        public ActionSetting getSetting(){return setting;}
        public boolean tick(){
            ticks++;
            if(setting.wait>0){setting.wait--;return false;}
            attempts++; setting.wait=setting.interval;
            if(failing) throw new IllegalStateException("fixture action failure");
            return false;
        }
        public void inactiveTick(){} public void stop(){stops++;}
    }
    @SuppressWarnings("unchecked")
    private Map<UUID,Map<ActionType,ActionTicker>> actionMap(ActionManager am) {
        try {var field=ActionManager.class.getDeclaredField("managers");field.setAccessible(true);return (Map<UUID,Map<ActionType,ActionTicker>>)field.get(am);}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    private CompletableFuture<Void> faultIntegrity() {
        return spawn("Fault51").thenCompose(p->{
            var am=Main.getInjector().getInstance(ActionManager.class);
            var broken=new FaultTicker(); var healthy=new FaultTicker(); healthy.failing=false;
            actionMap(am).put(p.getUniqueId(),new HashMap<>(Map.of(ActionType.ATTACK,broken,ActionType.JUMP,healthy)));
            return later(16,()->{
                check(broken.attempts==3 && broken.stops==1,"repeated action failure stops after three attempts despite cooldown ticks");
                check(!am.hasActiveAction(p,ActionType.ATTACK) && healthy.attempts>=4,"failed action isolated; other actions continue");
                var dm=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class);
                check(dm.find(Bukkit.getConsoleSender(),p.getName()).events.stream().anyMatch(e->e.contains("ACTION_STOPPED")),"action failure reason visible in diagnostics");
                check(!am.getActions(p).containsKey(ActionType.ATTACK),"stopped failure omitted from resident action snapshots");
                am.setAction(p,ActionType.JUMP,ActionSetting.continuous());
                check(healthy.stops==1,"replacing action cleans up previous ticker once");
                am.setAction(p,ActionType.ATTACK,ActionSetting.once());
            }).thenCompose(v->later(5,()->{
                check(am.hasActiveAction(p,ActionType.JUMP),"manual action command can start fresh ticker after failure");
                am.stop(p); check(am.getActiveActions(p).isEmpty(),"stop immediately clears all action registrations");
                manager.remove(p.getName(),"done");
            }));
        });
    }

    private record QuitHook(String stage,String name,String uuid,String creator,boolean online,int tick) {}
    private final List<QuitHook> quitHooks=new ArrayList<>();
    private final Set<String> hookAccounts=new HashSet<>();
    private final List<String> duplicateRegistrations=new ArrayList<>();
    private List<QuitHook> hookEvents(String name,String stage) {
        return quitHooks.stream().filter(e->e.name().equalsIgnoreCase(name)&&e.stage().equals(stage)).toList();
    }
    private CompletableFuture<Void> quitHooks() {
        quitHooks.clear(); hookAccounts.clear(); duplicateRegistrations.clear();
        return withConfig(Map.of("pre-spawn-commands",List.of("/fphook pre %p %u %c"),
                "post-quit-commands",List.of("fphook post %p %u %c"),
                "after-quit-commands",List.of("/fphook after %p %u %c","", "  "),"prevent-kicking","NEVER"),
                ()->spawn("QuitHook52").thenCompose(p->{
                    var uuid=p.getUniqueId().toString(); manager.remove(p.getName(),"quit hook fixture");
                    check(manager.get(p.getName())==null,"normal removal clears fake registry before deferred hook");
                    return later(25,()->{
                        check(hookEvents(p.getName(),"post").size()==1,"post-quit command runs once");
                        check(hookEvents(p.getName(),"after").size()==1,"after-quit command runs once after registry removal");
                        var after=hookEvents(p.getName(),"after").getFirst();
                        check(after.uuid().equals(uuid)&&after.creator().equals("CONSOLE"),"deferred placeholders retain original UUID and creator");
                        check(!after.online(),"deferred hook runs after player is offline");
                        check(!hookAccounts.contains(p.getName().toLowerCase(Locale.ROOT)),"mock registration removed after quit");
                    });
                }).thenCompose(v->spawn("QuickHook52")).thenCompose(p->{
                    manager.remove(p.getName(),"immediate reconnect");return spawn("quickhook52");
                }).thenCompose(p->later(25,()->{
                    check(duplicateRegistrations.isEmpty(),"immediate reconnect runs old cleanup before new registration");
                    check(hookAccounts.contains("quickhook52"),"old cleanup cannot erase new registration");
                    check(hookEvents(p.getName(),"after").size()==1,"old deferred task does not repeat after reconnect");
                    var events=quitHooks.stream().filter(e->e.name().equalsIgnoreCase(p.getName())).map(QuitHook::stage).toList();
                    check(events.equals(List.of("pre","post","after","pre")),"quit and reconnect command order is pre post after pre");
                    manager.remove(p.getName(),"done");
                })).thenCompose(v->later(3,()->check(hookEvents("quickhook52","after").size()==2,"second quit has its own exactly-once hook")))
                .thenCompose(v->spawn("ExternalHook52")).thenCompose(p->{
                    p.kick(net.kyori.adventure.text.Component.text("fixture external quit hook"));
                    return later(3,()->check(hookEvents(p.getName(),"after").size()==1,"external kick also runs after-quit hook once"));
                }).thenCompose(v->spawn("kickguardbot")).thenCompose(p->{
                    p.kick(net.kyori.adventure.text.Component.text("fixture external quit hook"));
                    return later(3,()->{
                        check(p.isOnline()&&hookEvents(p.getName(),"after").isEmpty(),"cancelled kick does not run quit hook");
                        manager.remove(p.getName(),"done");
                    });
                }).thenCompose(v->later(3,()->check(hookEvents("kickguardbot","after").size()==1,"later genuine quit still runs after-quit hook")))
                .thenCompose(v->spawn("SnapshotHook52")).thenCompose(p->{
                    manager.remove(p.getName(),"snapshot commands");
                    var commands=Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.config.FakeplayerConfig.class).getAfterQuitCommands();
                    commands.clear(); commands.add("fphook changed %p %u %c");
                    return later(3,()->{
                        check(hookEvents(p.getName(),"after").size()==1&&hookEvents(p.getName(),"changed").isEmpty(),"pending hook keeps command snapshot across config changes");
                        commands.clear(); commands.addAll(List.of("fphook explode %p %u %c","fphook tail %p %u %c"));
                    });
                }).thenCompose(v->spawn("ErrorHook52")).thenCompose(p->{
                    manager.remove(p.getName(),"hook command failure");
                    return later(3,()->check(hookEvents(p.getName(),"tail").size()==1,"one command throwing does not skip remaining after-quit commands"));
                }));
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) return true;
        if (command.getName().equals("fphook")) {
            if(args.length!=4) throw new IllegalArgumentException("hook requires stage name UUID creator");
            String name=args[1].toLowerCase(Locale.ROOT);
            if(args[0].equals("explode")) throw new IllegalStateException("fixture after-quit command failure");
            quitHooks.add(new QuitHook(args[0],args[1],args[2],args[3],Bukkit.getPlayerExact(args[1])!=null,Bukkit.getCurrentTick()));
            if(args[0].equals("pre")&&!hookAccounts.add(name)) duplicateRegistrations.add(name);
            if(args[0].equals("after")) hookAccounts.remove(name);
            return true;
        }
        results.clear();
        String phase = args.length == 0 ? "state" : args[0];
        try {
            CompletableFuture<Void> future = switch (phase) {
                case "quit-hooks" -> quitHooks(); case "integrity-resident-toggle" -> residentToggleIntegrity(); case "integrity-case" -> caseIntegrity(); case "integrity-nearest" -> nearestIntegrity(); case "integrity-refill" -> refillIntegrity(); case "integrity-action" -> faultIntegrity();
                case "state" -> state(); case "pending" -> pending(); case "resident" -> resident();
                case "restored" -> restored(); case "death" -> death(); case "actions" -> actions();
                case "management" -> withConfig(Map.of("detect-ip",false),this::management); case "conflicts" -> conflicts(); case "budgets" -> budgets(); case "closing" -> closing(); case "race" -> race(); case "skin-configured" -> skinConfigured(); case "journal-restored" -> journalRestored(); case "offline-management" -> offlineManagement();
                case "cancel" -> cancel(); case "respawn" -> respawn(); case "seed" -> seed(); case "unavailable" -> unavailable();
                default -> throw new IllegalArgumentException(phase);
            };
            future.whenComplete((v, error) -> {
                if (error != null) { results.add("FAIL " + error); getLogger().log(java.util.logging.Level.SEVERE, phase, error); }
                else results.add("PASS phase " + phase);
                try { Files.createDirectories(getDataFolder().toPath()); Files.write(getDataFolder().toPath().resolve(phase + ".txt"), results); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, phase, e);
            results.add("FAIL " + e);
            try { Files.createDirectories(getDataFolder().toPath()); Files.write(getDataFolder().toPath().resolve(phase + ".txt"), results); }
            catch (Exception error) { throw new RuntimeException(error); }
        }
        return true;
    }
}
