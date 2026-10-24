package io.github.hello09x.fakeplayer.core.manager.invsee;

import io.github.hello09x.fakeplayer.api.spi.*;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.command.Permission;
import io.github.hello09x.fakeplayer.core.manager.*;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.repository.model.Feature;
import io.github.hello09x.fakeplayer.core.util.FakeplayerAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import java.util.*;

/** Six-row live inventory plus an independent action page. All methods run on the server thread. */
public final class ControlInvseeManager extends AbstractInvseeManager {
    private static final ActionType[] ACTIONS={ActionType.ATTACK,ActionType.MINE,ActionType.USE,ActionType.JUMP};
    private static final String[] PERMISSIONS={Permission.attack,Permission.mine,Permission.use,Permission.jump};
    private static final String[] NAMES={"攻击","挖掘","右键","跳跃"}, ENGLISH={"Attack","Mine","Use","Jump"};
    private static final Material[] ICONS={Material.DIAMOND_SWORD,Material.DIAMOND_PICKAXE,Material.LEVER,Material.RABBIT_FOOT};
    private static final String[] MODES={"停止","单次","连续","间隔"}, MODES_EN={"Stop","Once","Continuous","Interval"};
    private static final Material[] COLORS={Material.RED_STAINED_GLASS_PANE,Material.LIGHT_BLUE_STAINED_GLASS_PANE,Material.LIME_STAINED_GLASS_PANE,Material.ORANGE_STAINED_GLASS_PANE};
    private final NMSBridge bridge;
    private final ActionManager actions;
    private final Map<UUID,Session> sessions=new HashMap<>();

    public static final class Session implements InventoryHolder {
        public final Player viewer,target;
        private final Inventory display;
        private InventoryView view;
        private boolean actionPage, syncPending;
        private final int[] periods={20,20,20,20};
        private final boolean[] intervalModes=new boolean[4];
        private Session(Player viewer,Player target) {
            this.viewer=viewer; this.target=target;
            display=Bukkit.createInventory(this,54);
        }
        @Override public Inventory getInventory() { return view==null?display:view.getTopInventory(); }
        private int map(int slot) {
            if(actionPage) return -1;
            if(slot>=9&&slot<=35) return slot;
            if(slot>=36&&slot<=44) return slot-36;
            return switch(slot) { case 0->39; case 1->38; case 2->37; case 3->36; case 5->40; default->-1; };
        }
        private String text(String zh,String en) { return viewer.locale().getLanguage().equals("zh")?zh:en; }
    }

    public ControlInvseeManager(FakeplayerManager manager,FakeplayerList list,NMSBridge bridge,ActionManager actions) {
        super(manager,list); this.bridge=bridge; this.actions=actions;
        Bukkit.getScheduler().runTaskTimer(Main.getInstance(),this::refresh,2,2);
    }

    private boolean allowed(Session s) {
        var fake=fakeplayerList.getByUUID(s.target.getUniqueId());
        return s.viewer!=s.target && s.viewer.isOnline() && s.target.isOnline() && !s.target.isDead()
                && fake!=null && fake.getPlayer()==s.target && s.viewer.getWorld().equals(s.target.getWorld())
                && s.viewer.hasPermission(Permission.invsee)
                && (FakeplayerAccess.managesOthers(s.viewer)||fake.isCreatedBy(s.viewer));
    }

    @Override protected InventoryView openInventory(Player viewer,Player target) {
        var session=new Session(viewer,target);
        if(!allowed(session)) return null;
        var current=actions.getActions(target);
        for(int i=0;i<4;i++) if(current.containsKey(ACTIONS[i])) session.periods[i]=guiPeriod(current.get(ACTIONS[i]));
        paint(session);
        session.view=bridge.openPlayerControl(viewer,target,session.display,session::map,()->allowed(session));
        if(session.view==null) return null;
        sessions.put(viewer.getUniqueId(),session);
        return session.view;
    }

    @Override protected Component title(Player whom) { return Component.text(whom.getName()+" · 背包与装备"); }

    private static ItemStack icon(Material material,String name,String... description) {
        var item=new ItemStack(material);
        item.editMeta(meta->{
            meta.displayName(Component.text(name,NamedTextColor.WHITE).decoration(TextDecoration.ITALIC,false));
            meta.lore(Arrays.stream(description).map(line->Component.text(line,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());
        });
        return item;
    }
    private static final Material GREY=Material.GRAY_STAINED_GLASS_PANE;
    private void put(Session s,int slot,Material material,String name,String... lore) { s.display.setItem(slot,icon(material,name,lore)); }
    private String row(Session s,int i) { return s.text(NAMES[i],ENGLISH[i]); }
    private String mode(Session s,int i) { return s.text(MODES[i],MODES_EN[i]); }
    private static int guiPeriod(ActionSetting setting) { return (int)Math.max(1,Math.min(1200L,(long)setting.interval+1)); }
    private void paint(Session s) {
        for(int i=0;i<54;i++) if(s.map(i)<0) put(s,i,GREY," ");
        if(!s.actionPage) {
            put(s,4,Material.CYAN_STAINED_GLASS_PANE,s.text("装备与双手","Equipment and hands"),
                    s.text("左侧依次：头盔、胸甲、护腿、靴子","Left: helmet, chestplate, leggings, boots"),
                    s.text("右侧第 6 格：副手（可编辑）","Slot 6: offhand (editable)"),
                    s.text("第 7 格：主手预览（只读）","Slot 7: main-hand preview (read-only)"));
            var held=s.target.getInventory().getItemInMainHand();
            if(held.isEmpty()) put(s,6,Material.LIGHT_BLUE_STAINED_GLASS_PANE,s.text("主手：空","Main hand: empty"));
            else {
                var preview=held.clone();
                preview.editMeta(meta->{
                    var lore=new ArrayList<Component>(); if(meta.lore()!=null) lore.addAll(meta.lore());
                    lore.add(Component.text(s.text("主手预览 · 只读","Main-hand preview · read-only"),NamedTextColor.AQUA).decoration(TextDecoration.ITALIC,false));
                    lore.add(Component.text(s.text("对应快捷栏 ","Hotbar slot ")+(s.target.getInventory().getHeldItemSlot()+1),NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false));
                    meta.lore(lore);
                });
                s.display.setItem(6,preview);
            }
            put(s,7,Material.EXPERIENCE_BOTTLE,s.text("状态与布局","Status and layout"),
                    s.text("第 2–4 行：背包；第 5 行：快捷栏","Rows 2–4: storage; row 5: hotbar"),
                    s.text("底行玻璃：点击选择快捷栏","Bottom glass: select hotbar slot"),
                    s.text("经验：","XP: ")+s.target.calculateTotalExperiencePoints(),
                    s.text("物品实时同步；关闭界面不会覆盖存档","Live items; no close-time inventory overwrite"));
            put(s,8,Material.PURPLE_STAINED_GLASS_PANE,s.text("动作控制 →","Actions →"),s.text("点击进入动作页","Click to open actions"));
            for(int i=0;i<9;i++) {
                boolean selected=s.target.getInventory().getHeldItemSlot()==i;
                put(s,45+i,selected?Material.LIME_STAINED_GLASS_PANE:GREY,
                        (selected?"✓ ":"")+s.text("快捷栏 ","Hotbar ")+(i+1),
                        s.text(selected?"当前主手格":"点击选择此格",selected?"Current main-hand slot":"Click to select"),
                        s.text("需要权限：","Permission: ")+Permission.hold);
            }
        } else {
            put(s,0,Material.WHITE_STAINED_GLASS_PANE,s.text("动作","Action"));
            for(int i=0;i<4;i++) put(s,1+i,COLORS[i],mode(s,i));
            put(s,5,GREY,"−"); put(s,6,Material.CLOCK,"tick"); put(s,7,GREY,"+");
            put(s,8,Material.CHEST,s.text("← 背包","← Inventory"));
            var running=actions.getActions(s.target);
            for(int i=0;i<4;i++) {
                int start=9+i*9; var setting=running.get(ACTIONS[i]);
                if(setting==null) s.intervalModes[i]=false;
                if(setting!=null&&setting.interval>0) s.intervalModes[i]=true;
                int selected=setting==null?0:setting.maximum==1?1:setting.interval==0&&!s.intervalModes[i]?2:3;
                if(setting!=null&&s.intervalModes[i]) s.periods[i]=guiPeriod(setting);
                put(s,start,ICONS[i],row(s,i),s.text("需要权限：","Permission: ")+PERMISSIONS[i]);
                for(int m=0;m<4;m++) put(s,start+1+m,selected==m?COLORS[m]:GREY,(selected==m?"✓ ":"")+mode(s,m),
                        s.text("点击设置","Click to set"),s.text("需要权限：","Permission: ")+PERMISSIONS[i]);
                put(s,start+5,GREY,"−",s.text("左键 −1；右键 −5；Shift −20 tick","Left −1; right −5; Shift −20 ticks"));
                long shownPeriod=setting!=null&&setting.maximum==-1&&s.intervalModes[i]?Math.max(1L,(long)setting.interval+1):s.periods[i];
                put(s,start+6,Material.CLOCK,shownPeriod+" tick",s.text("20 TPS 时约 ","At 20 TPS: ~")+String.format(Locale.ROOT,"%.2f",shownPeriod/20.0)+s.text(" 秒"," s"),
                        s.text("按钮调节范围：1–1200 tick","Button adjustment range: 1–1200 ticks"),
                        shownPeriod>1200?s.text("当前命令周期超出范围；调节后改用界面范围","Current command exceeds this range; editing applies the GUI range"):s.text("间隔模式使用此周期","Interval mode uses this period"));
                put(s,start+7,GREY,"+",s.text("左键 +1；右键 +5；Shift +20 tick","Left +1; right +5; Shift +20 ticks"));
                put(s,start+8,COLORS[selected],row(s,i)+" · "+mode(s,selected));
            }
            String[] labels={s.text("潜行","Sneak"),s.text("疾跑","Sprint"),s.text("补货","Replenish"),s.text("钓鱼","Autofish"),s.text("驻留","Resident")};
            boolean[] states={s.target.isSneaking(),s.target.isSprinting(),Boolean.parseBoolean(Feature.replenish.getDetector().apply(s.target)),Boolean.parseBoolean(Feature.autofish.getDetector().apply(s.target)),Boolean.parseBoolean(Feature.resident.getDetector().apply(s.target))};
            for(int i=0;i<5;i++) put(s,45+i,states[i]?Material.LIME_STAINED_GLASS_PANE:GREY,labels[i]+" · "+s.text(states[i]?"开启":"关闭",states[i]?"On":"Off"));
            put(s,51,Material.BOOK,s.text("操作说明","Help"),s.text("模式、间隔和开关均检查相应权限","Modes, periods and toggles check their permissions"));
            put(s,52,Material.RED_STAINED_GLASS_PANE,s.text("停止全部动作","Stop all actions"),s.text("不删除假人，不取消驻留","Keeps player, items and residency"),"Permission: "+Permission.stop);
        }
    }

    private Session session(InventoryView view) { return view.getTopInventory().getHolder() instanceof Session s?s:null; }
    private void closeLater(Session s) { Bukkit.getScheduler().runTask(Main.getInstance(),()->{if(s.viewer.getOpenInventory()==s.view)s.viewer.closeInventory();}); }
    private boolean permission(Session s,String permission) {
        if(s.viewer.hasPermission(permission)) return true;
        s.viewer.sendMessage(Component.text(s.text("缺少权限：","Missing permission: ")+permission,NamedTextColor.RED)); return false;
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void click(InventoryClickEvent event) {
        var s=session(event.getView()); if(s==null) return;
        if(!allowed(s)) { event.setCancelled(true); closeLater(s); return; }
        int slot=event.getRawSlot();
        if(slot<0||slot>=54||s.map(slot)>=0) return;
        event.setCancelled(true);
        if(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT&&event.getClick()!=ClickType.SHIFT_LEFT&&event.getClick()!=ClickType.SHIFT_RIGHT) return;
        try {
            if(slot==8) {
                s.actionPage=!s.actionPage;
                s.view.setTitle(s.target.getName()+s.text(s.actionPage?" · 动作控制":" · 背包与装备",s.actionPage?" · Actions":" · Inventory"));
            } else if(!s.actionPage && slot>=45 && permission(s,Permission.hold)) s.target.getInventory().setHeldItemSlot(slot-45);
            else if(s.actionPage && slot>=9 && slot<45) {
                int row=(slot-9)/9, column=slot%9;
                if(column>=1&&column<=7 && permission(s,PERMISSIONS[row])) {
                    var latest=actions.getActions(s.target).get(ACTIONS[row]);
                    if(latest!=null&&latest.maximum==-1&&latest.interval>0) {
                        s.periods[row]=guiPeriod(latest); s.intervalModes[row]=true;
                    }
                    if(column<=4) {
                        s.intervalModes[row]=column==4;
                        ActionSetting setting=switch(column) {
                            case 1->ActionSetting.stop(); case 2->ActionSetting.once(); case 3->ActionSetting.continuous();
                            default->ActionSetting.interval(s.periods[row]-1);
                        };
                        if(column!=1 && ACTIONS[row]==ActionType.USE && s.target.getInventory().getItemInMainHand().getType()==Material.FISHING_ROD && Boolean.parseBoolean(Feature.autofish.getDetector().apply(s.target))) setting=ActionSetting.once();
                        actions.setAction(s.target,ACTIONS[row],setting);
                    } else if(column==5||column==7) {
                        int step=event.isShiftClick()?20:event.isRightClick()?5:1;
                        s.periods[row]=Math.max(1,Math.min(1200,s.periods[row]+(column==5?-step:step)));
                        var running=actions.getActions(s.target).get(ACTIONS[row]);
                        if(running!=null && running.maximum==-1 && s.intervalModes[row]) actions.setAction(s.target,ACTIONS[row],ActionSetting.interval(s.periods[row]-1));
                    }
                }
            } else if(s.actionPage && slot>=45&&slot<=49) {
                switch(slot) {
                    case 45->{if(permission(s,Permission.sneak))s.target.setSneaking(!s.target.isSneaking());}
                    case 46->{if(permission(s,Permission.sprint))s.target.setSprinting(!s.target.isSprinting());}
                    case 47->toggle(s,Feature.replenish);
                    case 48->toggle(s,Feature.autofish);
                    case 49->toggle(s,Feature.resident);
                }
            } else if(s.actionPage && slot==52 && permission(s,Permission.stop)) actions.stop(s.target);
            paint(s);
            if(slot==8) bridge.resetPlayerControl(s.viewer);
        } catch(Exception error) {
            Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,"Fakeplayer GUI operation failed",error);
            s.viewer.sendMessage(Component.text(s.text("操作失败，请查看控制台日志","Operation failed; see server log"),NamedTextColor.RED));
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void resyncControls(InventoryClickEvent event) {
        var s=session(event.getView()); int raw=event.getRawSlot();
        if(s==null||raw<0||raw>=54||s.map(raw)>=0||s.syncPending) return;
        // A generic chest client may predict pickup even when the server's custom slot forbids it.
        // Send authoritative slots/cursor after Paper has resumed remote updates, once per session/tick.
        s.syncPending=true;
        Bukkit.getScheduler().runTask(Main.getInstance(),()->{
            s.syncPending=false;
            if(s.viewer.getOpenInventory()==s.view&&allowed(s)) bridge.resetPlayerControl(s.viewer);
        });
    }

    private void toggle(Session s,Feature feature) {
        if(feature!=Feature.resident&&!permission(s,Permission.set)) return;
        if(!feature.testPermissions(s.viewer)) {
            s.viewer.sendMessage(Component.text(s.text("缺少功能权限：","Missing feature permissions: ")+String.join(", ",feature.getPermissions()),NamedTextColor.RED)); return;
        }
        feature.getModifier().accept(s.target,String.valueOf(!Boolean.parseBoolean(feature.getDetector().apply(s.target))));
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drag(InventoryDragEvent event) {
        var s=session(event.getView()); if(s==null) return;
        if(!allowed(s)) {event.setCancelled(true);closeLater(s);return;}
        if(event.getRawSlots().stream().anyMatch(slot->slot<54&&s.map(slot)<0)) event.setCancelled(true);
    }
    @EventHandler public void closed(InventoryCloseEvent event) {
        var s=session(event.getView()); if(s!=null) sessions.remove(s.viewer.getUniqueId(),s);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        for(var s:new ArrayList<>(sessions.values())) if(s.target==event.getPlayer()) s.viewer.closeInventory();
    }
    private void refresh() {
        for(var s:new ArrayList<>(sessions.values())) {
            if(s.viewer.getOpenInventory()!=s.view) {sessions.remove(s.viewer.getUniqueId(),s);continue;}
            if(!allowed(s)) {s.viewer.closeInventory();continue;}
            paint(s);
        }
    }
    @Override public void close() { for(var s:new ArrayList<>(sessions.values())) s.viewer.closeInventory(); sessions.clear(); }
}
