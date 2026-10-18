package io.github.hello09x.fakeplayer.v26_3.network;

import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerManager;
import io.github.hello09x.fakeplayer.core.network.FakeChannel;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.util.logging.Logger;

public class FakeConnection extends Connection {

    private final static Logger log = Main.getInstance().getLogger();
    private final FakeplayerManager manager = Main.getInjector().getInstance(FakeplayerManager.class);

    public FakeConnection(@NotNull InetAddress address) {
        super(PacketFlow.SERVERBOUND);
        this.channel = new FakeChannel(null, address);
        this.address = this.channel.remoteAddress();
        Connection.configureSerialization(this.channel.pipeline(), PacketFlow.SERVERBOUND, false, null);
    }

    @Override
    public boolean isConnected() {
        // 26.3: Connection#tick() 只有在 !isConnected() 时才会执行 handleDisconnection(),
        // 这里必须反映 channel 的真实状态, 否则 /fp kill 永远无法把假人移除。
        return this.channel != null && this.channel.isOpen();
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        super.disconnect(details);
        // 假人没有 netty 的 channelInactive 事件, 主动补一次断开处理
        this.handleDisconnection();
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener channelfuturelistener) {
        this.complete(channelfuturelistener);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener channelfuturelistener, boolean flag) {
        this.complete(channelfuturelistener);
    }



    @Override
    public void send(Packet<?> packet) {

    }

    /**
     * 26.3 的断开流程是:
     * <pre>
     * connection.send(ClientboundDisconnectPacket, PacketSendListener.thenRun(() -> connection.disconnect(details)));
     * </pre>
     * 如果这里只是把数据包丢掉而不回调监听器, {@code connection.disconnect()} 永远不会执行,
     * 假人就无法被移除 (/fp kill 无效)。因此立即以成功的结果回调该监听器。
     */
    private void complete(@Nullable ChannelFutureListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.operationComplete(this.channel.newSucceededFuture());
        } catch (Exception e) {
            log.warning("Failed to complete packet send listener: " + e.getMessage());
        }
    }

}