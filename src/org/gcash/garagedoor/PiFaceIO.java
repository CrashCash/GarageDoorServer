package org.gcash.garagedoor;

import com.pi4j.component.light.LED;
import com.pi4j.component.relay.Relay;
import com.pi4j.device.piface.impl.PiFaceDevice;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import com.pi4j.io.spi.SpiChannel;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.gcash.garagedoor.GarageDoor.log;
import static org.gcash.garagedoor.GarageDoor.log_local;

// all the I/O related actions
public class PiFaceIO {
    private String fileDisarm = "/tmp/disarmed";
    private String soundCmd = "/usr/bin/play -q /usr/share/sounds/";
    public static final String CLOSED = "CLOSED";
    public static final String OPEN = "OPEN";
    public static final String TRANSIT = "TRANSIT";
    public static final String UNKNOWN = "UNKNOWN";

    public static final String CLEAR = "CLEAR";
    public static final String BLOCKED = "BLOCKED";

    public static final String ARMED = "ARMED";
    public static final String DISARMED = "DISARMED";

    public PiFaceDevice piface;
    private Relay relayButton;
    public LED ledWait;
    public LED ledStatus;
    public LED ledMotor;
    public LED ledBeam;
    public LED ledTransit;
    private GpioPinDigitalInput pinClosed;
    private GpioPinDigitalInput pinOpen;
    private GpioPinDigitalInput pinDoor;
    private GpioPinDigitalInput pinBeam;
    private GpioPinDigitalInput pinBtn0;
    private GpioPinDigitalInput pinBtn1;

    private long last_time;

    WatchdogTask watchdog;

    // debounce sensors
    boolean doorSemaphore = false;

    // list of various sound filenames
    private static final Map<String, String> sounds = Collections.unmodifiableMap(
            new HashMap<String, String>() {
        {
            put("tink", "tink.wav");
            put("ding", "ding.wav");
            put("bell", "bicycle_bell.wav");
            put("close start", "Window_DeIconify.wav");
            put("close done", "Window_Iconify.wav");
            put("beam clear", "Desktop6.wav");
            put("beam blocked", "Desktop7.wav");
            put("error", "defaultbeep.wav");
        }
    });

    // for testing
    private boolean flagDoor = false;

    public PiFaceIO() throws Exception {
        // initialize PiFace and set up access
        piface = new PiFaceDevice(SpiChannel.CS0);

        // relay to press motor button
        relayButton = piface.getRelay(0);

        // status LEDs
        ledWait = piface.getLed(7);
        ledStatus = piface.getLed(6);
        ledMotor = piface.getLed(5);
        ledBeam = piface.getLed(4);
        ledTransit = piface.getLed(3);

        // magnetic sensor inputs
        pinClosed = piface.getInputPin(4);
        pinOpen = piface.getInputPin(5);
        pinDoor = piface.getInputPin(6);
        pinBeam = piface.getInputPin(7);

        // user buttons
        pinBtn0 = piface.getInputPin(0);
        pinBtn1 = piface.getInputPin(1);

        // for sound effects, LED, and status task
        pinBeam.addListener(new beamListener());

        // respond to user buttons
        pinBtn0.addListener(new btn0Listener());
        pinBtn1.addListener(new btn1Listener());

        // for LED, status task
        pinClosed.addListener(new closeListener());
        pinOpen.addListener(new openListener());
        pinDoor.addListener(new doorListener());
    }

    // don't get dicked over by sleep stupidity
    public static void sleepSimple(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (Exception ex) {
        }
    }

    // play sound file
    public void sound(String sound) {
        String fn = sounds.get(sound);
        if (fn == null) {
            log("Unknown sound: " + sound);
            return;
        }

        try {
            Runtime.getRuntime().exec(soundCmd + fn);
        } catch (Exception ex) {
            log("Sound exception: " + ex);
        }
    }

    // read rollup door status
    public String statusRollup() {
        boolean closed = pinClosed.isLow();
        boolean open = pinOpen.isLow();
        if (!closed && !open) {
            return TRANSIT;
        }
        if (closed && !open) {
            return CLOSED;
        }
        if (!closed && open) {
            return OPEN;
        }
        return UNKNOWN;
    }

    // read back-door status
    public String statusDoor() {
        // return pinDoor.isLow() ? CLOSED : OPEN;
        flagDoor = !flagDoor;
        return flagDoor ? CLOSED : OPEN;
    }

    // read beam status
    public String statusBeam() {
        return pinBeam.isLow() ? BLOCKED : CLEAR;
    }

    // read armed status
    public String statusArmed() {
        return GarageDoor.closeTaskRunning ? ARMED : DISARMED;
    }

    // toggle relay to "push button" on motor
    public void pressButton() {
        log("press button");
        ledMotor.on();
        if ((new File(fileDisarm)).exists()) {
            sleepSimple(4);
            ledMotor.off();
            log("exiting - disarmed");
            return;
        }
        relayButton.close();
        sleepSimple(0.25);
        relayButton.open();
        ledMotor.off();
    }

    // watch the beam breaks and then close the door
    public void startCloseTask() {
        if (!GarageDoor.closeTaskRunning) {
            (new CloseTaskThread()).start();
        }
    }

    public void stopCloseTask() {
        GarageDoor.closeTaskRunning = false;
    }

    class CloseTaskThread extends Thread {
        @Override
        public void run() {
            log("close task run");
            sound("close start");
            GarageDoor.closeTaskRunning = true;
            GarageDoor.statusTasks();
            ledWait.on();
            // open the door if it's closed
            if (statusRollup().equals(CLOSED)) {
                pressButton();
                // delay so we don't instantly exit because the door is closed
                sleepSimple(2);
            }
            log("close task waiting to close door");
            last_time = 0;
            while (GarageDoor.closeTaskRunning) {
                if ((last_time != 0) &&
                    (System.currentTimeMillis() - last_time > GarageDoor.close_time) &&
                    statusBeam().equals("CLEAR")) {
                    // timeout since last beam-clear exceeded, and beam is currently clear
                    if (statusRollup().equals(OPEN)) {
                        log("close task closing door");
                        pressButton();
                    } else {
                        log("door is not open: " + statusRollup());
                    }
                    stopCloseTask();
                }
                if (statusRollup().equals(CLOSED)) {
                    // if door is closed, we're wasting time
                    log("close task exiting because door is closed");
                    stopCloseTask();
                }
                sleepSimple(0.1);
            }
            ledWait.off();
            GarageDoor.statusTasks();
            sound("close done");
            log("close task done");
        }
    }

    // send email after door is open for extended period of time
    class WatchdogTask extends Thread {
        @Override
        public void run() {
            while (true) {
                sleepSimple(60 * 60);
                if (statusRollup().equals(CLOSED)) {
                    break;
                }

                // send email
                try {
                    String[] command = {"/bin/bash", "-c", "echo \"Garage door is open\" | /usr/bin/mail -s \"Garage door is open\" genecash@fastmail.com"};
                    Runtime.getRuntime().exec(command);
                    log("door is open - sent email");
                } catch (IOException ex) {
                    log("door is open - could not send email");
                }
            }
        }
    }

    // handle photoelectric eye events
    public class beamListener implements GpioPinListenerDigital {
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
            GarageDoor.statusTasks();
            if (event.getState() == PinState.HIGH) {
                last_time = System.currentTimeMillis();
                log_local("beam clear");
                ledBeam.off();
                if (GarageDoor.closeTaskRunning) {
                    sound("beam clear");
                }
            } else {
                last_time = 0;
                log_local("beam blocked");
                ledBeam.on();
                if (GarageDoor.closeTaskRunning) {
                    sound("beam blocked");
                }
            }
        }
    }

    // listen to back door sensor
    public class doorListener implements GpioPinListenerDigital {
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
            GarageDoor.statusTasks();
            if (event.getState() == PinState.HIGH) {
                log("back door opened");
            } else if (event.getState() == PinState.LOW) {
                log("back door closed");
            }
        }
    }

    // listen to door-open sensor
    public class openListener implements GpioPinListenerDigital {
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
            if (doorSemaphore) {
                return;
            }
            doorSemaphore = true;
            GarageDoor.statusTasks();
            if (event.getState() == PinState.HIGH) {
                ledTransit.on();
                sound("bell");
                log("rollup door closing");
            } else if (event.getState() == PinState.LOW) {
                ledTransit.off();
                sound("bell");
                log("rollup door open");
                watchdog = new WatchdogTask();
                watchdog.start();
            }
            sleepSimple(2);
            doorSemaphore = false;
        }
    }

    // listen to door-close sensor
    public class closeListener implements GpioPinListenerDigital {
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
            if (doorSemaphore) {
                return;
            }
            doorSemaphore = true;
            GarageDoor.statusTasks();
            if (event.getState() == PinState.HIGH) {
                ledTransit.on();
                sound("bell");
                log("rollup door opening");
            } else if (event.getState() == PinState.LOW) {
                ledTransit.off();
                sound("bell");
                log("rollup door closed");
                watchdog.interrupt();
            }
            sleepSimple(2);
            doorSemaphore = false;
        }
    }

    // handle button press events for button #1
    public class btn0Listener implements GpioPinListenerDigital {
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
            if (event.getState() == PinState.LOW) {
                log("button 1 press");
                if (GarageDoor.closeTaskRunning) {
                    // if we're already waiting, abort
                    log("button 1 aborted task");
                    stopCloseTask();
                } else {
                    // if door is closed, then open & wait for beam break
                    log("button 1 starting task");
                    startCloseTask();
                }
            }
        }
    }

    // handle button press events for button #2
    public class btn1Listener implements GpioPinListenerDigital {
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
            if (event.getState() == PinState.LOW) {
                log("button 2 press");
                pressButton();
            }
        }
    }
}
