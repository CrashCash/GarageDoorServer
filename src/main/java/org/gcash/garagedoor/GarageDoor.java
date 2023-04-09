/*
** test with:
telnet -z ssl -z cert=~/raspberry_pi/garage_door/tls/cert-client.pem -z key=~/raspberry_pi/garage_door/tls/key-client.pem raspi 16000
telnet -z ssl -z cert=~/raspberry_pi/garage_door/tls/cert-client.pem -z key=~/raspberry_pi/garage_door/tls/key-client.pem garagedoor 17000
 */
package org.gcash.garagedoor;

import com.pi4j.component.light.LED;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import static org.gcash.garagedoor.Main.log;

public class GarageDoor extends ChannelInboundHandlerAdapter {
    private String keyFile = "/etc/garagedoor/key-server.pem";
    private String certFile = "/etc/garagedoor/cert-server.pem";
    private String clientCertFile = "/etc/garagedoor/cert-client.pem";
    public static PiFaceIO pifaceIO = null;

    // this is global so we only have one close task running
    public static boolean closeTaskRunning = false;

    // a set of all the running status tasks
    public static Set<ServerHandler.StatusTask> statusTasks = new HashSet<>();

    // config variables
    public static int close_time = 4000;
    public static int port = 17000;

    // initialize things
    public GarageDoor() throws Exception {
        final SslContext sslCtx;

        // set up TLS client authorization
        sslCtx = SslContextBuilder
                .forServer(new File(certFile), new File(keyFile)) // my identification
                .trustManager(new File(clientCertFile)) // clients that I trust
                .clientAuth(ClientAuth.REQUIRE) // require client id - very important
                .build();

        // set up pool of network servers
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ServerInitializer(sslCtx));

            // light show to indicate we're ready
            // note that LEDs 0 & 1 are connected to the relays
            LED[] lights = pifaceIO.piface.getLeds();
            for (int i = 2; i < lights.length; i++) {
                lights[i].on();
            }
            PiFaceIO.sleepSimple(1);
            for (int i = 2; i < lights.length; i++) {
                lights[i].off();
            }

            // this never returns
            bootstrap.bind(port).sync().channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.terminationFuture().sync();
            workerGroup.terminationFuture().sync();
            log("Server terminated");
        }
    }

    // interrupt all the running status tasks so they update
    public static void statusTasks() {
        for (ServerHandler.StatusTask task : statusTasks) {
            task.interrupt();
        }
    }
}
