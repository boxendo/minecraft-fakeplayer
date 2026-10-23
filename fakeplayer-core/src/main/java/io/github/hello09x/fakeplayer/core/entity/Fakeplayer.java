package io.github.hello09x.fakeplayer.core.entity;

import io.github.hello09x.devtools.command.exception.CommandException;
import io.github.hello09x.fakeplayer.core.util.ServerTasks;
import io.github.hello09x.fakeplayer.api.spi.*;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.config.PreventKicking;
import io.github.hello09x.fakeplayer.core.constant.MetadataKeys;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerAutofishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerReplenishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerSkinManager;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.manager.naming.SequenceName;
import io.github.hello09x.fakeplayer.core.util.Attributes;
import io.github.hello09x.fakeplayer.core.util.InternalAddressGenerator;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnknownNullability;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public class Fakeplayer {

    private final static InternalAddressGenerator ipGen = new InternalAddressGenerator();
    private final static FakeplayerConfig config = Main.getInjector().getInstance(FakeplayerConfig.class);
    private final static NMSBridge bridge = Main.getInjector().getInstance(NMSBridge.class);
    private final static FakeplayerSkinManager skinManager = Main.getInjector().getInstance(FakeplayerSkinManager.class);
    private final static FakeplayerReplenishManager replenishManager = Main.getInjector().getInstance(FakeplayerReplenishManager.class);
    private final static FakeplayerAutofishManager autofishManager = Main.getInjector().getInstance(FakeplayerAutofishManager.class);
    private final static ActionManager actionManager = Main.getInjector().getInstance(ActionManager.class);


    @NotNull
    @Getter
    private final CommandSender creator;

    @NotNull
    @Getter
    private final NMSServerPlayer handle;

    @NotNull
    @Getter
    private final Player player;

    @NotNull
    @Getter
    private final String creatorIp;

    @NotNull
    @Getter
    private final SequenceName sequenceName;

    @NotNull
    private final FakeplayerTicker ticker;

    @Getter
    @NotNull
    private final String name;

    @NotNull
    private final UUID uuid;

    /**
     * 是否驻留：插件关闭时保存状态，服务器重启后原地恢复
     */
    @Getter
    @Setter
    private boolean resident;

    @Getter
    private volatile boolean spawnCancelled;

    @Getter
    @Setter
    private boolean retainInventoryOnQuit;

    public void cancelSpawn() {
        this.spawnCancelled = true;
        Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.SpawnCoordinator.class).cancel(this);
    }

    @Getter
    @UnknownNullability
    private NMSNetwork network;

    /**
     * @param creator      创建者
     * @param creatorIp    创建者 IP
     * @param sequenceName 序列名
     * @param lifespan     存活时间
     */
    public Fakeplayer(
            @NotNull CommandSender creator,
            @NotNull String creatorIp,
            @NotNull SequenceName sequenceName,
            long lifespan
    ) {
        this.name = sequenceName.name();
        this.uuid = sequenceName.uuid();

        this.creator = creator;
        this.creatorIp = creatorIp;
        this.sequenceName = sequenceName;
        this.handle = bridge.fromServer(Bukkit.getServer()).newPlayer(uuid, name);
        this.player = handle.getPlayer();

        this.ticker = new FakeplayerTicker(this, lifespan);
        this.player.setPersistent(config.isPersistData());
        this.player.setSleepingIgnored(true);
        this.handle.setPlayBefore(); // 可避免一些插件的第一次入服欢迎信息
        this.handle.disableAdvancements(Main.getInstance()); // 不提示成就信息
    }

    /**
     * 让假人诞生
     */
    public CompletableFuture<Void> spawnAsync(@NotNull SpawnOption option) {
        return spawnAsync(option, false);
    }

    public CompletableFuture<Void> spawnAsync(@NotNull SpawnOption option, boolean restoring) {
        this.resident = option.resident();
        return Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.SpawnCoordinator.class).submit(this, option, restoring);
    }

    /** Internal entry point: the coordinator has admitted the job and prepared its target chunk. */
    public CompletableFuture<Void> spawnNowAsync(@NotNull SpawnOption option) {
        var diagnostics = Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class);
        diagnostics.stage(this, "PRE_LOGIN", "触发异步登录检查");
        var address = ipGen.next();
        this.player.setMetadata(MetadataKeys.SPAWNED_AT, new FixedMetadataValue(Main.getInstance(), Bukkit.getCurrentTick()));
        return ServerTasks.async(() -> {
                    var event = this.callPreLoginEvent(address);
                    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                        throw new CommandException(translatable(
                                "fakeplayer.command.spawn.error.disallowed",
                                text(player.getName(), WHITE), event.kickMessage()
                        ).color(RED));
                    }
                }).thenCompose(ignored -> ServerTasks.call(() -> {
                    if (this.spawnCancelled || Bukkit.isStopping()) {
                        throw new IllegalStateException("Spawn cancelled: " + this.name);
                    }
                    diagnostics.stage(this, "LOAD_DATA", "读取原生玩家存档");
                    // In 26.3 placeNewPlayer no longer loads playerdata.
                    if (config.isPersistData()) {
                        this.player.loadData();
                    }
                    this.player.setPersistent(config.isPersistData());
                    {
                        diagnostics.stage(this, "LOGIN", "触发登录检查");
                        var event = this.callLoginEvent(address);
                        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED && config.getPreventKicking().ordinal() < PreventKicking.ON_SPAWNING.ordinal()) {
                            throw new CommandException(translatable(
                                    "fakeplayer.command.spawn.error.disallowed", RED,
                                    text(player.getName(), WHITE),
                                    event.kickMessage()
                            ));
                        }
                    }

                    if (config.isDropInventoryOnQuiting()) {
                        // 跨服背包同步插件可能导致假人既丢弃了一份到地上，在重新生成的时候又回来了
                        // 因此在生成的时候清空一次背包
                        // 但无法解决登陆后延迟同步背包的情况
                        this.player.getInventory().clear();
                    }

                    this.player.setInvulnerable(option.invulnerable());
                    this.player.setCollidable(option.collidable());
                    this.player.setCanPickupItems(option.pickupItems());
                    if (option.lookAtEntity()) {
                        actionManager.setAction(player, ActionType.LOOK_AT_NEAREST_ENTITY, ActionSetting.continuous());
                    }
                    if (option.skin()) {
                        skinManager.useDefaultSkin(creator, player);
                    }
                    if (option.replenish()) {
                        replenishManager.setReplenish(player, true);
                    }
                    if (option.autofish()) {
                        autofishManager.setAutofish(player, true);
                    }

                    diagnostics.stage(this, "JOINING", "加入服务端");
                    this.network = bridge.createNetwork(address);
                    this.network.placeNewPlayer(Bukkit.getServer(), this.player);
                    if (!this.player.isOnline()) {
                        throw new IllegalStateException("Fake player disconnected during login: " + this.name);
                    }
                    if (option.wolverine()) {
                        io.github.hello09x.fakeplayer.core.repository.model.Feature.wolverine
                                .getModifier().accept(player, "true");
                    }
                    if (this.player.getHealth() <= 0) {
                        this.player.setHealth(Optional.ofNullable(this.player.getAttribute(Attributes.maxHealth()))
                                .map(AttributeInstance::getValue).orElse(20D));
                    }
                    this.setupName();
                    this.handle.setupClientOptions();   // 处理皮肤设置问题

                    diagnostics.stage(this, "TELEPORT", "放置到目标位置");
                    return this.player.teleportAsync(option.spawnAt().clone());
                })).thenCompose(teleport -> teleport).thenCompose(success -> ServerTasks.call(() -> {
                    if (!success || !this.player.isOnline() || this.spawnCancelled) {
                        throw new IllegalStateException("Could not place fake player at spawn location: " + this.name);
                    }
                    this.ticker.runTaskTimer(Main.getInstance(), 0, 1);
                    int inventoryHash = java.util.Arrays.hashCode(this.player.getInventory().getContents());
                    Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
                        if (player.isOnline() && inventoryHash != java.util.Arrays.hashCode(player.getInventory().getContents())) {
                            diagnostics.event(this, "INVENTORY", "登录后背包发生变化；正常拾取、动作或同步均可能导致，未确定来源", org.bukkit.event.player.PlayerJoinEvent.getHandlerList());
                        }
                    }, 20);
                    return null;
                }));
    }

    public boolean isOnline() {
        return this.player.isOnline();
    }

    public int getTickCount() {
        return handle.getTickCount();
    }

    private @NotNull AsyncPlayerPreLoginEvent callPreLoginEvent(@NotNull InetAddress address) {
        var event = new AsyncPlayerPreLoginEvent(
                this.name,
                address,
                this.uuid
        );
        Bukkit.getPluginManager().callEvent(event);
        Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class)
                .event(this, "PRE_LOGIN", event.getLoginResult()+": "+plain(event.kickMessage()), event.getHandlers());
        return event;
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return component == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private @NotNull PlayerLoginEvent callLoginEvent(@NotNull InetAddress address) {
        var event = new PlayerLoginEvent(
                player,
                address.getHostAddress(),
                address
        );
        Bukkit.getPluginManager().callEvent(event);
        Main.getInjector().getInstance(io.github.hello09x.fakeplayer.core.manager.diagnostic.DiagnosticManager.class)
                .event(this, "LOGIN", event.getResult()+": "+plain(event.kickMessage())+"; prevent-kicking="+config.getPreventKicking(), event.getHandlers());
        return event;
    }

    /**
     * 判断是否是创建者
     * <p>如果玩家下线再重新登陆, entityID 将会不一样导致 {@link Object#equals(Object)} 返回 {@code false}</p>
     *
     * @param sender 命令执行者
     * @return 是否是创建者
     */
    public boolean isCreatedBy(@NotNull CommandSender sender) {
        return io.github.hello09x.fakeplayer.core.util.FakeplayerAccess.owns(sender,
                io.github.hello09x.fakeplayer.core.util.FakeplayerAccess.creatorId(this.creator), this.creator.getName());
    }

    public @NotNull UUID getUUID() {
        return uuid;
    }

    private void setupName() {
        var displayName = text(player.getName(), config.getNameStyleColor(), config.getNameStyleDecorations().toArray(new TextDecoration[0]));
        player.playerListName(displayName);
        player.displayName(displayName);
        player.customName(displayName);
    }
}
