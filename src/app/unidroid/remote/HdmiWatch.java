package app.unidroid.remote;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto HDMI: when the device plugged into HDMI wakes up (Apple TV, console…), switch the
 * projector to it. Android's HDMI-CEC service polls connected devices every 15 s and logs
 * their power status (1 standby, 0 on) and the active source; this follows that log
 * instead of polling anything itself. Off unless turned on (flag file).
 */
final class HdmiWatch {
    private static final File FLAG = new File("/data/local/tmp/unidroid/autohdmi");
    private static final Pattern POWER = Pattern.compile("power_status: (-?\\d+)");
    private static final Pattern ACTIVE = Pattern.compile("activeSource: \\((\\w+),");

    private final Actions actions;
    private Thread thread;
    private int lastPower = Integer.MIN_VALUE;
    private boolean lastActive;

    HdmiWatch(Actions actions) {
        this.actions = actions;
    }

    static boolean enabled() {
        return FLAG.exists();
    }

    synchronized void set(boolean on) {
        try {
            if (on) FLAG.createNewFile();
            else FLAG.delete();
        } catch (Exception ignored) {
            // Best effort.
        }
        start();
    }

    /** Starts following the log once; it keeps running and checks the flag on each event. */
    synchronized void start() {
        if (thread != null || !enabled()) return;
        thread = new Thread(this::follow, "udr-hdmi-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private void follow() {
        while (true) {
            try {
                Process p = new ProcessBuilder("logcat", "-v", "brief", "-T", "1",
                        "Hdmi-HotPlugDetectionAction:D", "HdmiCecLocalDevice:D", "*:S").redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) onLine(line);
                }
            } catch (Exception ignored) {
                // logcat died: start it again below.
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void onLine(String line) {
        boolean wake = false;
        Matcher m = POWER.matcher(line);
        if (line.contains("targetInfo") && m.find()) {
            int power = Integer.parseInt(m.group(1));
            // From standby (or unknown) to on.
            if (power == 0 && lastPower != 0 && lastPower != Integer.MIN_VALUE) wake = true;
            lastPower = power;
        }
        Matcher a = ACTIVE.matcher(line);
        if (a.find()) {
            boolean active = !"invalid".equals(a.group(1));
            if (active && !lastActive) wake = true;
            lastActive = active;
        }
        if (wake && enabled() && !onHdmi()) actions.run("hdmi");
    }

    /** True when the HDMI input is already on screen. */
    private static boolean onHdmi() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "dumpsys activity activities | grep -m1 mResumedActivity").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l = r.readLine();
                return l != null && (l.contains("awlivetv") || l.contains("awsource"));
            }
        } catch (Exception e) {
            return false;
        }
    }
}
