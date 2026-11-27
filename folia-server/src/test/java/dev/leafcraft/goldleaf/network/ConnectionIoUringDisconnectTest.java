package dev.leafcraft.goldleaf.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.channel.uring.IoUringSocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link Connection#disconnect(DisconnectionDetails)} does not deadlock or throw
 * {@link io.netty.util.concurrent.BlockingOperationException} when invoked on a Netty event-loop
 * thread whose transport completes {@code channel.close()} asynchronously (such as {@code io_uring}).
 */
class ConnectionIoUringDisconnectTest {

    @Test
    void disconnectOnEventLoopWithAsyncCloseDoesNotThrowAndPreservesDetails() throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        LocalAddress addr = new LocalAddress("goldleaf-async-close-test-" + System.nanoTime());
        Channel serverChannel = null;
        Channel clientChannel = null;
        try {
            serverChannel = new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // no-op peer
                    }
                })
                .bind(addr)
                .syncUninterruptibly()
                .channel();

            clientChannel = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // Defer channel.close() completion onto the next event-loop tick,
                        // reproducing io_uring's async IORING_OP_CLOSE incomplete ChannelPromise.
                        ch.pipeline().addFirst("deferred-async-close", new ChannelOutboundHandlerAdapter() {
                            @Override
                            public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                                ctx.executor().execute(() -> ctx.close(promise));
                            }
                        });
                    }
                })
                .connect(addr)
                .syncUninterruptibly()
                .channel();

            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            connection.channel = clientChannel;
            DisconnectionDetails details = new DisconnectionDetails(Component.literal("fuzz_disconnect_reason"));

            clientChannel.eventLoop().submit(() -> connection.disconnect(details)).get(5, TimeUnit.SECONDS);

            assertNotNull(connection.getDisconnectionDetails(), "DisconnectionDetails must be recorded");
            assertEquals("fuzz_disconnect_reason", connection.getDisconnectionDetails().reason().getString());
            assertTrue(connection.becomeActive(), "Folia becomeActive flag must be set on disconnect");
            assertTrue(clientChannel.closeFuture().await(5, TimeUnit.SECONDS), "Async channel close must finish");
            assertFalse(connection.isConnected());
        } finally {
            if (clientChannel != null) {
                clientChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
            if (serverChannel != null) {
                serverChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void disconnectOffEventLoopStillAwaitsCloseAndPreservesDetails() throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        LocalAddress addr = new LocalAddress("goldleaf-off-loop-close-test-" + System.nanoTime());
        Channel serverChannel = null;
        Channel clientChannel = null;
        try {
            serverChannel = new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // no-op peer
                    }
                })
                .bind(addr)
                .syncUninterruptibly()
                .channel();

            clientChannel = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addFirst("deferred-async-close", new ChannelOutboundHandlerAdapter() {
                            @Override
                            public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                                ctx.executor().execute(() -> ctx.close(promise));
                            }
                        });
                    }
                })
                .connect(addr)
                .syncUninterruptibly()
                .channel();

            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            connection.channel = clientChannel;
            DisconnectionDetails details = new DisconnectionDetails(Component.literal("off_loop_kick"));

            // Invoked from test thread (off-event-loop): must block until close completes.
            connection.disconnect(details);

            assertFalse(connection.isConnected(), "Off-event-loop disconnect must await channel close");
            assertNotNull(connection.getDisconnectionDetails());
            assertEquals("off_loop_kick", connection.getDisconnectionDetails().reason().getString());
            assertTrue(connection.becomeActive());
        } finally {
            if (clientChannel != null) {
                clientChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
            if (serverChannel != null) {
                serverChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void disconnectOnRealIoUringEventLoopDoesNotThrowBlockingOperationException() throws Exception {
        Assumptions.assumeTrue(IoUring.isAvailable(), "Native Netty io_uring transport not available");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory());
        Channel serverChannel = null;
        Channel clientChannel = null;
        try {
            AtomicReference<Channel> accepted = new AtomicReference<>();
            serverChannel = new ServerBootstrap()
                .group(group)
                .channel(IoUringServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        accepted.set(ch);
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel();

            InetSocketAddress bound = (InetSocketAddress) serverChannel.localAddress();
            clientChannel = new Bootstrap()
                .group(group)
                .channel(IoUringSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // no-op
                    }
                })
                .connect(bound)
                .syncUninterruptibly()
                .channel();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (accepted.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Channel serverPeer = accepted.get();
            assertNotNull(serverPeer, "Server should accept loopback io_uring connection");

            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            connection.channel = serverPeer;
            DisconnectionDetails details = new DisconnectionDetails(Component.literal("io_uring_disconnect_ok"));

            serverPeer.eventLoop().submit(() -> connection.disconnect(details)).get(5, TimeUnit.SECONDS);

            assertNotNull(connection.getDisconnectionDetails());
            assertEquals("io_uring_disconnect_ok", connection.getDisconnectionDetails().reason().getString());
            assertTrue(connection.becomeActive());
            assertTrue(serverPeer.closeFuture().await(5, TimeUnit.SECONDS));
            assertFalse(connection.isConnected());
        } finally {
            if (clientChannel != null) {
                clientChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
            if (serverChannel != null) {
                serverChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }
}
