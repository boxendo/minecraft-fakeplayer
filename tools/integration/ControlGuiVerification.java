package verification;

import io.github.hello09x.fakeplayer.api.spi.*;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.command.Permission;
import io.github.hello09x.fakeplayer.core.manager.*;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.manager.invsee.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import net.minecraft.world.inventory.ContainerInput;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Test-only real Paper menu transaction and Bukkit event checks. */
public final class ControlGuiVerification {
    private final JavaPlugin plugin;
    private final BiConsumer<Boolean,String> check;
    private final FakeplayerManager manager=Main.getInjector().getInstance(FakeplayerManager.class);
    private final InvseeManager gui=Main.getInjector().getInstance(InvseeManager.class);
    private final ActionManager actions=Main.getInjector().getInstance(ActionManager.class);
    private Player a,b,target;
    private PacketSpy packets;
    private int beforeSelectionContent;
    private static final class PacketSpy extends io.github.hello09x.fakeplayer.v26_3.network.FakeServerGamePacketListenerImpl {
        int fullContents;
        PacketSpy(Player player) {
            super(((org.bukkit.craftbukkit.CraftServer)Bukkit.getServer()).getServer(),
                    ((CraftPlayer)player).getHandle().connection.connection,
                    ((CraftPlayer)player).getHandle(),
                    net.minecraft.server.network.CommonListenerCookie.createInitial(((CraftPlayer)player).getProfile(),false));
            ((CraftPlayer)player).getHandle().connection=this;
        }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
            if(packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket) fullContents++;
            super.send(packet);
        }
    }
    public ControlGuiVerification(JavaPlugin plugin,BiConsumer<Boolean,String> check) {this.plugin=plugin;this.check=check;}
    private CompletableFuture<Player> spawn(org.bukkit.command.CommandSender owner,String name) {
        return manager.spawnAsync(owner,name,new Location(Bukkit.getWorlds().getFirst(),900,-60,900),-1);
    }
    private CompletableFuture<Void> later(int ticks,Runnable action) {
        var future=new CompletableFuture<Void>();
        Bukkit.getScheduler().runTaskLater(plugin,()->{try{action.run();future.complete(null);}catch(Throwable error){future.completeExceptionally(error);}},ticks);
        return future;
    }
    private void ok(boolean value,String text) {check.accept(value,text);}
    private org.bukkit.craftbukkit.inventory.CraftInventoryView<?,?> view(Player p) {return (org.bukkit.craftbukkit.inventory.CraftInventoryView<?,?>)p.getOpenInventory();}
    private void nativeClick(Player p,int slot,int button,ContainerInput input) {
        var handle=((CraftPlayer)p).getHandle();handle.containerMenu.clicked(slot,button,input,handle);
    }
    private InventoryClickEvent button(Player p,int slot) {return button(p,slot,ClickType.LEFT);}
    private InventoryClickEvent button(Player p,int slot,ClickType click) {
        var event=new InventoryClickEvent(p.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,click,InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(event);return event;
    }
    private int amount(Player p,Material material) {return p.getInventory().all(material).values().stream().mapToInt(ItemStack::getAmount).sum();}
    private void fixtures() {
        for(var p:List.of(a,b,target)){p.setInvulnerable(true);p.setCanPickupItems(false);p.getInventory().clear();}
        target.getInventory().setItem(9,new ItemStack(Material.DIAMOND,7));
        target.getInventory().setItem(0,new ItemStack(Material.DIAMOND_SWORD));
        target.getInventory().setItem(1,new ItemStack(Material.STONE,64));
        target.getInventory().setHeldItemSlot(0);
        target.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        target.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        target.getInventory().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
        target.getInventory().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        target.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
    }
    public CompletableFuture<Void> shutdownSeed() {
        manager.remove("GuiStopViewer60","reset shutdown fixture");
        manager.remove("GuiStopTarget60","reset shutdown fixture");
        return spawn(Bukkit.getConsoleSender(),"GuiStopViewer60").thenCompose(viewer->{
            viewer.setOp(true);
            return spawn(Bukkit.getConsoleSender(),"GuiStopTarget60").thenAccept(player->{
                player.setInvulnerable(true);player.setCanPickupItems(false);player.getInventory().clear();
                player.getInventory().setItem(9,new ItemStack(Material.DIAMOND,13));
                ok(gui.invsee(viewer,player),"shutdown fixture opens GUI");
                nativeClick(viewer,9,0,ContainerInput.PICKUP);nativeClick(viewer,10,0,ContainerInput.PICKUP);
                Main.getInjector().getInstance(ResidentManager.class).setResident(player,true);
                ok(player.getInventory().getItem(10).getAmount()==13&&viewer.getItemOnCursor().isEmpty(),"shutdown fixture edits resident inventory through open GUI");
                ok(viewer.getOpenInventory().getTopInventory().getHolder() instanceof ControlInvseeManager.Session,"GUI left open for normal shutdown regression");
            });
        });
    }
    public CompletableFuture<Void> shutdownRestored() {
        var player=manager.get("GuiStopTarget60");
        ok(player!=null&&player.isOnline(),"resident edited in open GUI restored after normal shutdown");
        ok(player.getInventory().getItem(9)==null&&player.getInventory().getItem(10).getType()==Material.DIAMOND&&player.getInventory().getItem(10).getAmount()==13,"open GUI inventory edits survive shutdown exactly once");
        ok(manager.get("GuiStopViewer60")==null,"GUI viewer does not become resident accidentally");
        manager.remove(player.getName(),"shutdown fixture cleanup");return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> run() {
        ok(gui instanceof ControlInvseeManager,"AUTO selects built-in control GUI");
        return spawn(Bukkit.getConsoleSender(),"GuiOwner60").thenCompose(p->{a=p;a.setOp(true);return spawn(Bukkit.getConsoleSender(),"GuiOther60");})
                .thenCompose(p->{b=p;b.setOp(true);return spawn(a,"GuiTarget60");})
                .thenCompose(p->{target=p;fixtures();
                    ok(gui.invsee(a,target),"owner opens live GUI");
                    ok(a.getOpenInventory().getTopInventory().getSize()==54,"GUI has exactly 54 top slots");
                    packets=new PacketSpy(a);
                    var top=a.getOpenInventory().getTopInventory();
                    ok(top.getItem(0).getType()==Material.DIAMOND_HELMET&&top.getItem(3).getType()==Material.DIAMOND_BOOTS,"armor slots map head-to-feet");
                    ok(top.getItem(5).getType()==Material.SHIELD&&top.getItem(9).getAmount()==7&&top.getItem(36).getType()==Material.DIAMOND_SWORD,"offhand storage and hotbar map to real player inventory");
                    ok(!target.getInventory().getItemInMainHand().getItemMeta().hasLore(),"main-hand preview lore never changes real item metadata");
                    nativeClick(a,9,1,ContainerInput.PICKUP);
                    ok(a.getItemOnCursor().getAmount()==4&&target.getInventory().getItem(9).getAmount()==3,"native right-click splits real stack");
                    nativeClick(a,10,0,ContainerInput.PICKUP);
                    ok(a.getItemOnCursor().isEmpty()&&target.getInventory().getItem(10).getAmount()==4,"native left-click places stack into real storage");
                    ok(gui.invsee(b,target),"delegated administrator opens second live view");
                    nativeClick(b,9,0,ContainerInput.PICKUP);nativeClick(a,9,0,ContainerInput.PICKUP);
                    ok(b.getItemOnCursor().getAmount()==3&&a.getItemOnCursor().isEmpty(),"two viewers cannot take same stack from stale snapshots");
                    nativeClick(b,9,0,ContainerInput.PICKUP);
                    nativeClick(a,10,0,ContainerInput.QUICK_MOVE);
                    ok(amount(a,Material.DIAMOND)==4&&target.getInventory().getItem(10)==null,"native shift-click moves real stack to viewer once");
                    a.getInventory().setItem(0,new ItemStack(Material.EMERALD));
                    nativeClick(a,36,0,ContainerInput.SWAP);
                    ok(target.getInventory().getItem(0).getType()==Material.EMERALD&&a.getInventory().getItem(0).getType()==Material.DIAMOND_SWORD,"number key swaps hotbar items without copying");
                    nativeClick(a,81,0,ContainerInput.QUICK_MOVE);
                    ok(target.getInventory().getItemInOffHand().getType()==Material.SHIELD&&a.getInventory().getItem(0)==null,"incoming shift-click skips offhand and decorations");
                    a.setItemOnCursor(new ItemStack(Material.IRON_INGOT,6));
                    nativeClick(a,-999,0,ContainerInput.QUICK_CRAFT);
                    nativeClick(a,11,1,ContainerInput.QUICK_CRAFT);nativeClick(a,12,1,ContainerInput.QUICK_CRAFT);
                    nativeClick(a,4,1,ContainerInput.QUICK_CRAFT);nativeClick(a,-999,2,ContainerInput.QUICK_CRAFT);
                    ok(a.getItemOnCursor().isEmpty()&&target.getInventory().getItem(11).getAmount()==3&&target.getInventory().getItem(12).getAmount()==3,"native drag distributes real items and skips control slot");
                    var glass=a.getOpenInventory().getTopInventory().getItem(45).clone();glass.setAmount(2);target.getInventory().setItem(13,glass.clone());
                    glass.setAmount(1);a.setItemOnCursor(glass);nativeClick(a,54,0,ContainerInput.PICKUP_ALL);
                    ok(a.getItemOnCursor().getAmount()==3&&a.getOpenInventory().getTopInventory().getItem(45).getAmount()==1,"double-click collects genuine matching items but excludes identical decorative glass");
                    a.setItemOnCursor(null);
                    nativeClick(a,0,0,ContainerInput.PICKUP);
                    ok((target.getInventory().getHelmet()==null||target.getInventory().getHelmet().isEmpty())&&a.getItemOnCursor().getType()==Material.DIAMOND_HELMET,"armor can be removed into cursor");
                    nativeClick(a,0,0,ContainerInput.PICKUP);
                    a.setItemOnCursor(new ItemStack(Material.STONE,2));nativeClick(a,2,0,ContainerInput.PICKUP);
                    ok(target.getInventory().getLeggings().getType()==Material.DIAMOND_LEGGINGS&&a.getItemOnCursor().getType()==Material.STONE,"armor slot rejects inappropriate item");
                    a.setItemOnCursor(null);a.setGameMode(GameMode.CREATIVE);
                    for(int slot:new int[]{4,6,7,8,45,46,47,48,49,50,51,52,53}) for(var input:List.of(ContainerInput.PICKUP,ContainerInput.QUICK_MOVE,ContainerInput.SWAP,ContainerInput.CLONE,ContainerInput.THROW)) nativeClick(a,slot,0,input);
                    ok(a.getItemOnCursor().isEmpty()&&amount(a,Material.LIME_STAINED_GLASS_PANE)==0&&amount(a,Material.EMERALD)==0,"all native extraction paths protect glass buttons and main-hand mirror");
                    a.setGameMode(GameMode.SURVIVAL);
                    beforeSelectionContent=packets.fullContents;
                    ok(button(a,46).isCancelled()&&target.getInventory().getHeldItemSlot()==1,"selection glass changes held slot and cancels item transfer");
                    return later(3,()->{
                        ok(a.getOpenInventory().getTopInventory().getItem(6).getType()==Material.STONE,"main-hand preview refreshes after selection");
                        ok(packets.fullContents>beforeSelectionContent,"cancelled control click sends authoritative content and cursor after event completion");
                        a.setItemOnCursor(new ItemStack(Material.GOLD_INGOT,2));button(a,8);
                        ok(a.getItemOnCursor().getAmount()==2,"changing pages retains cursor items");
                        nativeClick(a,0,0,ContainerInput.PICKUP);
                        ok(a.getItemOnCursor().getType()==Material.GOLD_INGOT,"action headers cannot replace cursor with decorations");
                        a.setItemOnCursor(null);
                        var matchingButton=a.getOpenInventory().getTopInventory().getItem(10).clone();matchingButton.setAmount(3);
                        a.getInventory().setItem(0,matchingButton);
                        nativeClick(a,81,0,ContainerInput.QUICK_MOVE);
                        ok(a.getInventory().getItem(0)!=null&&a.getInventory().getItem(0).getAmount()==3&&a.getOpenInventory().getTopInventory().getItem(10).getAmount()==1,"action page never absorbs genuine items with identical button metadata during shift merge");
                        a.getInventory().setItem(0,null);
                        button(a,13);var setting=actions.getActions(target).get(ActionType.ATTACK);
                        ok(setting!=null&&setting.interval==19&&setting.maximum==-1,"20 tick GUI period maps to native 19 tick cooldown");
                        button(a,16);ok(actions.getActions(target).get(ActionType.ATTACK).interval==20,"plus updates running interval immediately");
                        button(a,14,ClickType.RIGHT);ok(actions.getActions(target).get(ActionType.ATTACK).interval==15,"right-click adjusts interval by five ticks");
                        button(a,14,ClickType.SHIFT_LEFT);ok(actions.getActions(target).get(ActionType.ATTACK).interval==0&&a.getOpenInventory().getTopInventory().getItem(13).getType()==Material.ORANGE_STAINED_GLASS_PANE,"one tick interval clamps safely and keeps interval indicator");
                        button(a,39);button(a,10);
                        ok(!actions.hasActiveAction(target,ActionType.ATTACK)&&actions.hasActiveAction(target,ActionType.JUMP),"stopping one action leaves independent action running");
                        button(a,45);button(a,46);button(a,47);button(a,48);button(a,49);
                        ok(target.isSneaking()&&target.isSprinting(),"sneak and sprint buttons modify real player state");
                        ok(Main.getInjector().getInstance(FakeplayerReplenishManager.class).isReplenish(target)&&Main.getInjector().getInstance(FakeplayerAutofishManager.class).isAutofish(target),"feature toggles update replenish and autofish state");
                        ok(Main.getInjector().getInstance(ResidentManager.class).isResident(target),"resident toggle updates saved resident state");
                        var previousHand=target.getInventory().getItemInMainHand().clone();target.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
                        button(a,30);ok(actions.getActions(target).get(ActionType.USE).maximum==1,"autofishing rod keeps right-use action single-shot");
                        button(a,28);ok(!actions.hasActiveAction(target,ActionType.USE),"right-use stop remains effective while autofishing is enabled");
                        target.getInventory().setItemInMainHand(previousHand);
                        button(a,52);ok(actions.getActiveActions(target).isEmpty()&&Main.getInjector().getInstance(ResidentManager.class).isResident(target),"stop all preserves residency and items");
                        button(a,38);
                        actions.setAction(target,ActionType.ATTACK,ActionSetting.interval(Integer.MAX_VALUE));
                    });
                }).thenCompose(v->later(5,()->{
                    ok(!actions.hasActiveAction(target,ActionType.JUMP),"single jump completes without continuous repetition");
                    var periodText=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(a.getOpenInventory().getTopInventory().getItem(15).getItemMeta().displayName());
                    ok(periodText.equals("2147483648 tick"),"GUI displays actual external long interval without integer overflow");
                    button(a,14);ok(actions.getActions(target).get(ActionType.ATTACK).interval==1198,"editing external long interval safely enters supported GUI range");
                    button(a,10);
                    a.setOp(false);var permissions=a.addAttachment(plugin);
                    permissions.setPermission(Permission.invsee,true);permissions.setPermission(Permission.attack,false);permissions.setPermission(Permission.hold,false);
                    permissions.setPermission(Permission.sneak,false);permissions.setPermission(Permission.set,false);permissions.setPermission(Permission.resident,false);permissions.setPermission(Permission.stop,false);
                    button(a,13);button(a,45);button(a,47);button(a,49);
                    ok(!actions.hasActiveAction(target,ActionType.ATTACK)&&target.isSneaking(),"invsee alone does not grant action or feature permissions");
                    button(a,8);int held=target.getInventory().getHeldItemSlot();button(a,45);
                    ok(target.getInventory().getHeldItemSlot()==held,"hotbar selection respects hold permission");
                    var drag=new InventoryDragEvent(a.getOpenInventory(),new ItemStack(Material.STONE),new ItemStack(Material.STONE,2),false,Map.of(45,new ItemStack(Material.STONE)));
                    Bukkit.getPluginManager().callEvent(drag);ok(drag.isCancelled(),"drag across control glass is cancelled");
                    permissions.setPermission(Permission.invsee,false);
                    nativeClick(a,9,0,ContainerInput.PICKUP);
                    ok(a.getItemOnCursor().isEmpty()&&target.getInventory().getItem(9).getAmount()==3,"native slot guard blocks edits immediately after permission revocation");
                })).thenCompose(v->later(3,()->{
                    ok(!(a.getOpenInventory().getTopInventory().getHolder() instanceof ControlInvseeManager.Session),"revoked viewer is closed automatically");
                    a.setOp(true);gui.invsee(a,target);
                    target.getInventory().setItem(9,new ItemStack(Material.DIAMOND,11));
                    a.closeInventory();
                    ok(target.getInventory().getItem(9).getAmount()==11,"closing stale view never overwrites later inventory changes");
                    gui.invsee(a,target);a.setItemOnCursor(new ItemStack(Material.EMERALD,5));
                    manager.remove(target.getName(),"GUI disconnect fixture");
                    ok(!(a.getOpenInventory().getTopInventory().getHolder() instanceof ControlInvseeManager.Session)&&!(b.getOpenInventory().getTopInventory().getHolder() instanceof ControlInvseeManager.Session),"target quit closes both viewers");
                    ok(amount(a,Material.EMERALD)+(a.getItemOnCursor().getType()==Material.EMERALD?a.getItemOnCursor().getAmount():0)==5,"native close preserves cursor items exactly once in viewer inventory or carried slot");
                })).whenComplete((v,error)->{
                    for(var p:new Player[]{a,b,target}) if(p!=null){p.closeInventory();p.setOp(false);manager.remove(p.getName(),"GUI fixture cleanup");}
                });
    }
}
