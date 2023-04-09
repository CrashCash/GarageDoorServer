package org.gcash.garagedoor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.gcash.garagedoor.GarageDoor.pifaceIO;
import sun.misc.Signal;

public class Main {
    public static Logger logger;
    public static String subnet;

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

        // re-read config file on signal
        Signal.handle(new Signal("HUP"), (Signal signal) -> {
            log("Rereading config file");
            readConfig();
        });

        // determine our local subnet
        try {
            String ip_address = null;
            Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = (NetworkInterface) interfaces.nextElement();
                Enumeration addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = (InetAddress) addrs.nextElement();
                    if (!addr.isLoopbackAddress()) {
                        if (addr instanceof Inet4Address) {
                            ip_address = addr.getHostAddress();
                            subnet = "/" + ip_address.substring(0, ip_address.lastIndexOf('.') + 1);
                        }
                    }
                }
            }
        } catch (SocketException ex) {
        }

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

    // log messages cleanly
    public static void syslog(String msg, boolean remote) {
        //System.out.println("Log: (" + remote + ") " + msg);
        Runtime rt = java.lang.Runtime.getRuntime();
        try {
            // Java is such a piece of shit that it can't log to syslog
            if (remote) {
                // configure rsyslogd to log this remotely
                rt.exec("logger --priority local0.info --tag garagedoor " + msg).waitFor();
            } else {
                rt.exec("logger --tag garagedoor " + msg).waitFor();
            }
        } catch (Exception ex) {
            System.out.println("Logging error: " + ex);
            Logger.getLogger(GarageDoor.class.getName()).log(Level.SEVERE, null, ex);
        }
        //System.out.println("Log done");
    }

    public static void log(boolean isLocal, String msg) {
        if (!isLocal) {
            syslog(msg, true);
        }
    }

    public static void log(String msg) {
        syslog(msg, true);
    }

    public static void log_local(String msg) {
        syslog(msg, false);
    }

    // log as much exception info as possible
    public static void logExcept(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String msg = "Exception: " + sw.toString();
        logger.info(msg);
        log(msg);
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

    public static void readConfig() {
        BufferedReader br;
        try {
            br = new BufferedReader(new FileReader(new File("/etc/garagedoor/garagedoor.conf")));
        } catch (FileNotFoundException ex) {
            log("Config file not found");
            return;
        }

        try {
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.startsWith("#")) {
                    // handle comment
                    continue;
                }
                String[] split = line.toLowerCase().split(":", 2);
                String option = split[0].trim();
                try {
                    if (option.equals("close time")) {
                        float param = Float.parseFloat(split[1].trim());
                        if (param < 13) {
                            log("Close time must be longer than 13 seconds");
                        } else {
                            GarageDoor.close_time = (int) (param * 1000);
                            log("Close Time: " + param);
                        }
                    } else if (option.equals("port")) {
                        // network port
                        int param = Integer.parseInt(split[1].trim());
                        GarageDoor.port = param;
                        log("Port: " + param);
                    } else {
                        log("Unknown config item: \"" + option + "\"");
                    }
                } catch (NumberFormatException ex) {
                    log("readConfig exception in \"" + option + "\": " + ex);
                }
            }
        } catch (IOException ex) {
            log("readConfig exception: " + ex);
        }
    }
}
