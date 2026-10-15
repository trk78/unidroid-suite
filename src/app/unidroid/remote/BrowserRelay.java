package app.unidroid.remote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Bridge between the phones (WebSocket "w:" messages) and the browser of the UniDroid
 * app, which listens on 127.0.0.1:8090 only. Commands go one way, the browser's JSON
 * state comes back and is sent to every phone. Starts the browser when it is not open.
 */
final class BrowserRelay {
    interface Broadcast {
        void send(String message);
    }

    private static final int PORT = 8090;
    private static final String ACTIVITY = Actions.LAUNCHER + "/.BrowserActivity";

    private final Broadcast broadcast;
    private Socket socket;
    private OutputStream out;
    private volatile String last;

    BrowserRelay(Broadcast broadcast) {
        this.broadcast = broadcast;
        // The browser is usually opened from the projector, not the phone: notice it
        // (loopback connect, refused at once while closed) so the phones follow along.
        Thread watch = new Thread(() -> {
            while (true) {
                synchronized (this) {
                    if (out == null) connect(1);
                }
                sleep(2000);
            }
        }, "udr-browser-watch");
        watch.setDaemon(true);
        watch.start();
    }

    /** The browser's latest state line ("w:" + JSON) for a phone that just connected, or null. */
    String last() {
        return last;
    }

    /** Sends one command line; opens (or brings up) the browser first when needed. */
    synchronized void send(String command) {
        if (command.equals("show")) {
            Actions.exec("am", "start", "-n", ACTIVITY);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            if (out == null && !connect(attempt == 0 ? 1 : 25)) return;
            try {
                out.write((command.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            } catch (Exception e) {
                close();
            }
        }
    }

    /** Connects to the browser; on the retry, starts it and waits up to tries × 200 ms. */
    private boolean connect(int tries) {
        if (tries > 1) Actions.exec("am", "start", "-n", ACTIVITY);
        for (int i = 0; i < tries; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
                s.setTcpNoDelay(true);
                socket = s;
                out = s.getOutputStream();
                Thread t = new Thread(() -> read(s), "udr-browser");
                t.setDaemon(true);
                t.start();
                return true;
            } catch (Exception e) {
                if (tries > 1) sleep(200);
            }
        }
        return false;
    }

    private void read(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                last = "w:" + line;
                broadcast.send(last);
            }
        } catch (Exception ignored) {
            // Browser closed.
        }
        synchronized (this) {
            if (socket == s) close();
        }
        last = null;
        broadcast.send("w:{\"closed\":true}");
    }

    private void close() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
            // Already closed.
        }
        socket = null;
        out = null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // Ignore.
        }
    }
}
