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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GarageDoor extends ChannelInboundHandlerAdapter {
    private int port = 17000;
    private String keyFile = "/etc/garagedoor/key-server.pem";
    private String certFile = "/etc/garagedoor/cert-server.pem";
    private String clientCertFile = "/etc/garagedoor/cert-client.pem";
    private static Logger logger;
    public static PiFaceIO pifaceIO = null;

    // this is global so we only have one close task running
    public static boolean closeTaskRunning = false;

    // this is global so we only have one message task running
    public static boolean msgTaskRunning = false;

    // a set of all the running status tasks
    public static Set<ServerHandler.StatusTask> statusTasks = new HashSet<>();

    // config variables
    public static int close_time = 4000;

    // log messages cleanly
    public static void syslog(String msg, boolean remote) {
        Runtime rt = java.lang.Runtime.getRuntime();
        try {
            // Java is such a piece of shit that it can't log to syslog
            if (remote) {
                rt.exec("logger --rfc3164 -n desktop.lan -t garagedoor " + msg);
            }
            rt.exec("logger -t garagedoor " + msg);
        } catch (IOException ex) {
            Logger.getLogger(GarageDoor.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static void log(String msg) {
        syslog(msg, true);
    }

    public static void log_local(String msg) {
        syslog(msg, false);
    }

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
            pifaceIO.sleepSimple(1);
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

    // fetch version from jar file timestamp
    public static String version() {
        File f = new File(GarageDoor.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        Date lastModified = new Date(f.lastModified());
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a");
        return formatter.format(lastModified);
    }

    // fetch PID from procfs
    public static String pid() {
        try {
            return new File("/proc/self").getCanonicalFile().getName();
        } catch (IOException ex) {
            return null;
        }
    }

    // log as much exception info as possible
    public static void logExcept(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String msg = "Exception: " + sw.toString();
        logger.info(msg);
        log(msg);
    }

    // interrupt all the running status tasks so they update
    public static void interruptTasks() {
        for (ServerHandler.StatusTask task : statusTasks) {
            task.interrupt();
        }
    }

    public static void readConfig() {
        BufferedReader br;
        try {
            br = new BufferedReader(new FileReader(new File("/etc/garagedoor/garagedoor.conf")));
        } catch (FileNotFoundException ex) {
            return;
        }

        try {
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.startsWith("#")) {
                    // handle comment
                    continue;
                }
                String[] split = line.toLowerCase().split(":", 2);
                try {
                    if (split[0].equals("close time")) {
                        // guard time to close door
                        close_time = Integer.parseInt(split[1].trim());
                        log("close time set to: " + close_time);
                    } else {
                        log("Unknown config item: \"" + split[0] + "\"");
                    }
                } catch (NumberFormatException ex) {
                    log("readConfig number format exception in \"" + split[0] + "\": " + ex);
                }
            }
        } catch (IOException ex) {
            log("readConfig exception: " + ex);
        }
    }

    public static void main(String[] args) {
        // java is too retarded to log to the syslog daemon
        logger = Logger.getLogger(GarageDoor.class.getName());
        logger.setUseParentHandlers(false);

        FileHandler fh = null;
        try {
            fh = new FileHandler("/var/log/garagedoor%u%g.log", 256 * 1024, 50, true);
        } catch (Exception ex) {
            System.out.println("Error setting up logging: " + ex);
            System.exit(2);
        }
        logger.addHandler(fh);
        fh.setFormatter(new CustomLogFormatter());

        // ready to go
        log("Starting up: " + pid());
        readConfig();
        log("Version: " + version());

        // catches SIGINT (2) and SIGTERM (15)
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log("Shutdown");
            }
        });
        try {
            pifaceIO = new PiFaceIO();
            // this never returns
            new GarageDoor();
        } catch (Exception ex) {
            log("Exception in main:" + ex);
            System.exit(1);
        }
        log("Done");
    }
}
