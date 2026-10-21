package app.unidroid.remote;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A real USB-like mouse for the whole system, created through Android's own `hid` tool
 * (/dev/uhid, root). Android then draws its native pointer and every app gets hover,
 * clicks and wheel scrolling, which injected touch events cannot give.
 *
 * Reports are 5 bytes: buttons, x, y, wheel, horizontal pan (signed 8-bit deltas).
 */
final class VirtualMouse {
    private static final String HID = "/system/bin/hid";
    /** 3 buttons, relative X / Y / wheel, and AC Pan for horizontal scrolling. */
    private static final int[] DESCRIPTOR = {
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0
    };

    private Process process;
    private OutputStream in;
    private int buttons;

    static boolean available() {
        return new java.io.File(HID).canExecute();
    }

    /** dx, dy in screen pixels; long moves are split into ±127 steps. */
    synchronized void move(int dx, int dy) {
        while (dx != 0 || dy != 0) {
            int sx = clamp(dx), sy = clamp(dy);
            report(buttons, sx, sy, 0, 0);
            dx -= sx;
            dy -= sy;
        }
    }

    /** Wheel notches: positive v scrolls up, positive h scrolls right. */
    synchronized void scroll(int v, int h) {
        while (v != 0 || h != 0) {
            int sv = clamp(v), sh = clamp(h);
            report(buttons, 0, 0, sv, sh);
            v -= sv;
            h -= sh;
        }
    }

    /** button: 1 left, 2 right, 4 middle. */
    synchronized void press(int button, boolean down) {
        buttons = down ? buttons | button : buttons & ~button;
        report(buttons, 0, 0, 0, 0);
    }

    synchronized void click(int button) {
        press(button, true);
        press(button, false);
    }

    synchronized void close() {
        if (process != null) process.destroy();
        process = null;
        in = null;
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    private void report(int b, int x, int y, int wheel, int pan) {
        if (!ensure()) return;
        send("{\"id\":1,\"command\":\"report\",\"report\":[" + b + "," + (x & 0xFF) + "," + (y & 0xFF) + ","
                + (wheel & 0xFF) + "," + (pan & 0xFF) + "]}");
    }

    /** Starts `hid -` once and registers the device; restarts it if it died. */
    private boolean ensure() {
        if (process != null && process.isAlive()) return true;
        try {
            process = new ProcessBuilder(HID, "-").redirectErrorStream(true).start();
            in = process.getOutputStream();
            // Drain its output so it never blocks on a full pipe.
            Process p = process;
            Thread drain = new Thread(() -> {
                byte[] sink = new byte[512];
                try {
                    while (p.getInputStream().read(sink) >= 0) { /* discard */ }
                } catch (IOException ignored) {
                    // Gone.
                }
            }, "udr-hid-out");
            drain.setDaemon(true);
            drain.start();
            StringBuilder d = new StringBuilder();
            for (int v : DESCRIPTOR) d.append(d.length() == 0 ? "" : ",").append(v);
            send("{\"id\":1,\"command\":\"register\",\"name\":\"UniDroid Mouse\",\"vid\":4660,\"pid\":22136,"
                    + "\"bus\":\"usb\",\"descriptor\":[" + d + "]}"); // Android 11's hid needs the bus
            Thread.sleep(300); // let the input system add the device before the first report
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    private void send(String json) {
        try {
            in.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException | NullPointerException e) {
            close();
        }
    }
}
