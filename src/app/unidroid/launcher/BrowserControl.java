package app.unidroid.launcher;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Control channel of the browser: a socket on 127.0.0.1 only, reached by the remote
 * server, which relays the phone's commands (one line each) and sends the browser's
 * state (one JSON line) back to every phone. Nothing listens on the network here.
 */
final class BrowserControl {
    static final int PORT = 8090;

    interface Commands {
        /** Called on the main thread with one command line. */
        void command(String line);
    }

    private final Commands commands;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private volatile String lastState = "";

    BrowserControl(Commands commands) {
        this.commands = commands;
    }

    void start() {
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
                server.setReuseAddress(true);
                while (!server.isClosed()) {
                    Socket s = server.accept();
                    new Thread(() -> serve(s), "browser-client").start();
                }
            } catch (Exception ignored) {
                // Closed, or the port is taken by a previous instance that is going away.
            }
        }, "browser-control").start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
            // Already closed.
        }
        for (OutputStream o : clients) {
            try {
                o.close();
            } catch (Exception ignored) {
                // Gone.
            }
        }
        clients.clear();
    }

    /** Sends the state to every connected relay, and remembers it for newcomers. */
    void publish(String json) {
        lastState = json;
        byte[] line = (json + "\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream o : clients) {
            new Thread(() -> {
                try {
                    synchronized (o) {
                        o.write(line);
                        o.flush();
                    }
                } catch (Exception e) {
                    clients.remove(o);
                }
            }, "browser-publish").start();
        }
    }

    private void serve(Socket s) {
        OutputStream out = null;
        try (Socket socket = s) {
            out = socket.getOutputStream();
            clients.add(out);
            if (!lastState.isEmpty()) {
                synchronized (out) {
                    out.write((lastState + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                final String cmd = line;
                main.post(() -> commands.command(cmd));
            }
        } catch (Exception ignored) {
            // Relay gone.
        } finally {
            if (out != null) clients.remove(out);
        }
    }
}
