package org.gcash.garagedoor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import static org.gcash.garagedoor.Main.log;
import static org.gcash.garagedoor.Main.logExcept;
import static org.gcash.garagedoor.Main.log_local;
import static org.gcash.garagedoor.PiFaceIO.CLOSED;
import static org.gcash.garagedoor.PiFaceIO.OPEN;

// handle all the protocol actions
class ServerHandler extends SimpleChannelInboundHandler<String> {
    private ChannelHandlerContext conn;
    private boolean isLocal;
    // this is OUR status task information
    StatusTask statusTask = null;
    private boolean statusTaskRunning = false;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        isLocal = ctx.channel().remoteAddress().toString().startsWith(Main.subnet);
        log(isLocal, "Channel active");
        // new connection.
        conn = ctx;
        send("GARAGEDOOR");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log(isLocal, "Channel inactive");
        // connection closed, make sure our status task is stopped
        statusTaskRunning = false;
        if (statusTask != null) {
            statusTask.interrupt();
        }
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, String request) throws Exception {
        // ignore blank lines
        if (request.isEmpty()) {
            return;
        }

        conn = ctx;

        // excute method implementing command
        String command = request.toLowerCase();
        Method method = null;
        try {
            method = getClass().getDeclaredMethod("do_" + command);
        } catch (NoSuchMethodException e) {
            log("Unknown command: " + command);
            ctx.close();
            return;
        }
        method.invoke(this);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        log("Channel exception");
        InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
        String ip = addr.getAddress().getHostAddress();
        if (e instanceof NotSslRecordException) {
            // someone tried to ssh to our port
            log("Invalid connection from: " + ip);
            banHammer(ip);
        } else if (e instanceof DecoderException) {
            // bad (or no) certificate
            log("Invalid connection from: " + ip);
            banHammer(ip);
        } else if (e instanceof IOException) {
            // network connection went south
            log("Connection error: " + e.getMessage());
        } else {
            // no clue what happened
            logExcept(e);
        }
        ctx.close();
    }

    public void banHammer(String ip) {
        Runtime rt = java.lang.Runtime.getRuntime();
        try {
            if (rt.exec("fail2ban-client set sshd banip " + ip).waitFor() != 0) {
                log("Unable to ban: " + ip);
            } else {
                log("Banned: " + ip);
            }
        } catch (Exception ex) {
            log("Unable to ban: " + ip + " because " + ex.getMessage());
        }
    }

    // periodic task to report current state of doors
    public class StatusTask extends Thread {
        @Override
        public void run() {
            log(isLocal, "Status task start");
            statusTaskRunning = true;
            GarageDoor.pifaceIO.ledStatus.on();
            double sleep = 20;
            while (statusTaskRunning) {
                String stateNew = GarageDoor.pifaceIO.statusRollup() + " " +
                                  GarageDoor.pifaceIO.statusDoor() + " " +
                                  GarageDoor.pifaceIO.statusBeam() + " " +
                                  GarageDoor.pifaceIO.statusArmed();
                send("STATUS " + stateNew);
                // wait 20 seconds (or until we get interrupted when a status changes)
                if (PiFaceIO.sleepSimple(sleep) > 0) {
                    sleep = 0.5;
                } else {
                    sleep = 20;
                }
            }
            GarageDoor.pifaceIO.ledStatus.off();
            GarageDoor.statusTasks.remove(statusTask);
            statusTask = null;
            log(isLocal, "Status task done");
        }
    }

    // send EOL-terminated string
    private void send(String msg) {
        conn.writeAndFlush(msg + "\r\n");
        conn.flush();
    }

    /*
    *** remote command implementations ***
     */
    // arm/disarm the close-task
    private void do_arm() {
        log(isLocal, "Execute arm");
        if (GarageDoor.closeTaskRunning) {
            GarageDoor.closeTaskRunning = false;
        } else {
            GarageDoor.pifaceIO.startCloseTask();
        }
    }

    // close door if it's open
    private void do_close() {
        log(isLocal, "Execute close");
        if (GarageDoor.pifaceIO.statusRollup().equals(OPEN)) {
            GarageDoor.pifaceIO.pressButton();
        }
        send("CLOSE DONE");
    }

    // open door if it's closed
    private void do_open() {
        log(isLocal, "Execute open");
        if (GarageDoor.pifaceIO.statusRollup().equals(CLOSED)) {
            GarageDoor.pifaceIO.pressButton();
        }
        send("OPEN DONE");
    }

    // open door if it's closed, then close it after the beam is broken
    private void do_openclose() {
        log(isLocal, "Execute open/close");
        GarageDoor.pifaceIO.startCloseTask();
        send("OPENCLOSE DONE");
    }

    // keep-alive
    private void do_ping() {
        // do nothing
        log_local("ping");
    }

    // report current state of doors until the remote closes connection
    private void do_status() {
        log(isLocal, "Execute status");
        if (!statusTaskRunning) {
            statusTask = new StatusTask();
            statusTask.start();
            GarageDoor.statusTasks.add(statusTask);
        }
    }

    // open/close the door immediately
    private void do_toggle() {
        log(isLocal, "Execute toggle");
        GarageDoor.pifaceIO.pressButton();
        send("TOGGLE DONE");
    }
}
