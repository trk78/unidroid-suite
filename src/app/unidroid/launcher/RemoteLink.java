package app.unidroid.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The remote server (package app.unidroid.remote) ships inside this APK. RemoteLink
 * starts it as root with app_process, restarts it after an app update (new APK path),
 * and reads its state: the phones that opened the remote, and whether one of them has
 * added it to its home screen.
 */
final class RemoteLink {
    static final int PORT = 8080;
    private static final String SERVER = "app.unidroid.remote.Server";
    private static final String DIR = "/data/local/tmp/unidroid";

    static final class Device {
        String id, name;
        boolean installed, connected;
        long seen;
    }

    static final class State {
        /** The server answers. */
        boolean running;
        /** Address to open on a phone, e.g. http://192.168.1.87:8080 */
        String url;
        final List<Device> devices = new ArrayList<>();
        /** Switch to HDMI when the HDMI device wakes up. */
        boolean autoHdmi;
        /** When the sleep timer fires (epoch ms), 0 when off. */
        long sleepAt;

        boolean anyInstalled() {
            for (Device d : devices) if (d.installed) return true;
            return false;
        }
    }

    interface Callback {
        void state(State s);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean starting;

    private RemoteLink() {
    }

    /** Reads the state; starts or restarts the server when needed (root). */
    static void refresh(Context ctx, boolean ensureRunning, Callback cb) {
        final String apk = ctx.getApplicationInfo().sourceDir;
        new Thread(() -> {
            String json = get("/info");
            State s = parse(json);
            // Another refresh is (re)starting the server: wait for it instead of reporting it down.
            for (int i = 0; i < 20 && !s.running && starting; i++) {
                sleep(250);
                json = get("/info");
                s = parse(json);
            }
            boolean stale = s.running && json != null && !json.contains("\"apk\":\"" + apk + "\"");
            if (ensureRunning && (!s.running || stale) && !starting) {
                starting = true;
                Root.run(startCommand(apk), (ok, out) -> new Thread(() -> {
                    // Give the JVM a moment, then read again.
                    State after = new State();
                    for (int i = 0; i < 20 && !after.running; i++) {
                        sleep(250);
                        after = parse(get("/info"));
                    }
                    starting = false;
                    final State done = after;
                    MAIN.post(() -> cb.state(done));
                }, "remote-start").start());
                return;
            }
            final State now = s;
            MAIN.post(() -> cb.state(now));
        }, "remote-probe").start();
    }

    static void forget(String id, Runnable done) {
        new Thread(() -> {
            get("/forget?id=" + id);
            MAIN.post(done);
        }, "remote-forget").start();
    }

    /**
     * Stops any running copy (old and new servers share the process name unidroid-remote),
     * then starts ours detached. Also retires the standalone UniDroid Remote Magisk module,
     * whose boot loop would respawn the old server. The [/] keeps pkill from matching this
     * very command line; nothing else here may use pkill -f on a name this line contains.
     */
    private static String startCommand(String apk) {
        return "pkill -f '[/]data/adb/modules/unidroid-remote/service.sh'; rm -rf /data/adb/modules/unidroid-remote;"
                + " for p in $(pidof unidroid-remote); do kill $p; done;"
                + " mkdir -p " + DIR + "; sleep 0.3;"
                + " CLASSPATH=" + apk + " setsid app_process /system/bin --nice-name=unidroid-remote "
                + SERVER + " " + PORT + " " + apk + " </dev/null >" + DIR + "/server.log 2>&1 &";
    }

    private static State parse(String json) {
        State s = new State();
        s.url = "http://" + lanAddress() + ":" + PORT;
        if (json == null) return s;
        try {
            JSONObject o = new JSONObject(json);
            s.running = true;
            s.autoHdmi = o.optBoolean("autohdmi");
            s.sleepAt = o.optLong("sleepAt");
            JSONArray arr = o.optJSONArray("devices");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.getJSONObject(i);
                    Device dev = new Device();
                    dev.id = d.optString("id");
                    dev.name = d.optString("name", "Browser");
                    dev.installed = d.optBoolean("installed");
                    dev.connected = d.optBoolean("connected");
                    dev.seen = d.optLong("seen");
                    s.devices.add(dev);
                }
            }
            // Most recent first.
            Collections.sort(s.devices, (a, b) -> Long.compare(b.seen, a.seen));
        } catch (Exception e) {
            s.running = false;
        }
        return s;
    }

    /** Fire-and-forget GET on the local remote server, off the main thread. */
    static void call(String path) {
        new Thread(() -> get(path), "remote-call").start();
    }

    /** Blocking GET on the local remote server; null when it fails. Never on the main thread. */
    static String get(String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path).openConnection();
            c.setConnectTimeout(600);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                return buf.toString("UTF-8");
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return "127.0.0.1";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // Ignore.
        }
    }
}
