package org.gcash.garagedoor;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import static org.gcash.garagedoor.Main.log;

// set up server to handle single-line strings
class ServerInitializer extends ChannelInitializer<SocketChannel> {
    private final SslContext sslCtx;
    private static final StringDecoder DECODER = new StringDecoder();
    private static final StringEncoder ENCODER = new StringEncoder();

    public ServerInitializer(SslContext sslCtx) {
        log("Ready to accept connections");
        this.sslCtx = sslCtx;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(sslCtx.newHandler(ch.alloc()));
        pipeline.addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
        pipeline.addLast(DECODER);
        pipeline.addLast(ENCODER);
        pipeline.addLast(new ServerHandler());
        String addr = ch.remoteAddress().toString();
        if (!addr.startsWith(Main.subnet)) {
            log("Connection from: " + addr.substring(1));
        }
    }
}
