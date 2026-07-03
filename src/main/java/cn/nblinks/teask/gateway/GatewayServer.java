package cn.nblinks.teask.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Netty TCP server for charger connections. The pipeline is identical to the
 * original charger-iot-api NettyServer so real firmware framing is unchanged:
 * length-prefixed frames (SOP + 2-byte length at offset 1) → byte[] → handler.
 */
public final class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workGroup;

    public ChannelFuture start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // Drop a connection that goes silent for > ~2.5 ping cycles.
                        pipeline.addLast(new IdleStateHandler(
                                GatewayConfig.PING_CYCLE_SECONDS * 2 + 25, 0, 0, TimeUnit.SECONDS));
                        // SOP(1) | LEN(2) | ... — same frame decoder params as the original.
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                GatewayConfig.MAX_FRAME_LENGTH, 1, 2, 0, 0));
                        pipeline.addLast(new ByteArrayDecoder());
                        pipeline.addLast(new ByteArrayEncoder());
                        pipeline.addLast(new DeviceHandler());
                    }
                });

        ChannelFuture future = bootstrap.bind(GatewayConfig.TCP_PORT).sync();
        if (future.isSuccess()) {
            log.info("TCP gateway listening on port {}", GatewayConfig.TCP_PORT);
        }
        return future;
    }

    public void stop() {
        if (workGroup != null) workGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
