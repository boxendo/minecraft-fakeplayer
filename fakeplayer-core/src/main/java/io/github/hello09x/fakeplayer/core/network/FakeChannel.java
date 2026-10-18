package io.github.hello09x.fakeplayer.core.network;

import io.netty.channel.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class FakeChannel extends AbstractChannel {
    private final static EventLoop EVENT_LOOP = new DefaultEventLoop();
    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final ChannelPipeline pipeline = new FakeChannelPipeline(this);
    private final InetAddress address;

    /**
     * 是否已关闭。
     *
     * <p>服务端在 {@code Connection#handleDisconnection()} 中会检查 {@code !channel.isOpen()},
     * 只有返回 false 才会真正执行断开逻辑 (移除玩家、触发 PlayerQuitEvent 等)。
     * 因此这个状态必须真实反映 close()。</p>
     */
    private volatile boolean closed;

    public FakeChannel(@Nullable Channel parent, @NotNull InetAddress address) {
        super(parent);
        this.address = address;
    }

    @Override
    public ChannelConfig config() {
        config.setAutoRead(true);
        return config;
    }

    @Override
    protected void doBeginRead() throws Exception {
    }

    @Override
    protected void doBind(SocketAddress arg0) throws Exception {
    }

    @Override
    protected void doClose() throws Exception {
        this.closed = true;
    }

    /**
     * {@link FakeChannelPipeline} 是一个空实现, 它的 close() 直接返回成功但不会调用
     * {@link #doClose()}, 导致 channel 永远是 "打开" 状态, 服务端的断开流程不会执行。
     * 这里显式处理 close, 让状态真实流转。
     */
    @Override
    public ChannelFuture close() {
        this.closed = true;
        return super.newSucceededFuture();
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        this.closed = true;
        promise.setSuccess();
        return promise;
    }

    @Override
    protected void doDisconnect() throws Exception {
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (; ; ) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            in.remove();
        }
    }

    @Override
    public boolean isActive() {
        return !this.closed;
    }

    @Override
    protected boolean isCompatible(EventLoop arg0) {
        return true;
    }

    @Override
    public boolean isOpen() {
        return !this.closed;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    protected SocketAddress localAddress0() {
        return new InetSocketAddress(address, 25565);
    }

    @Override
    public ChannelMetadata metadata() {
        return new ChannelMetadata(true);
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                safeSetSuccess(promise);
            }
        };
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return new InetSocketAddress(address, 25565);
    }

    @Override
    public EventLoop eventLoop() {
        return EVENT_LOOP;
    }
}
