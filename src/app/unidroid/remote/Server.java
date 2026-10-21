package app.unidroid.remote;

import android.os.Build;
import android.os.Looper;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipFile;

/**
 * UniDroid Remote server: serves the web UI and accepts commands over a WebSocket (/ws)
 * or plain HTTP GET (/key, /text, /action). Commands never go through a shell.
 *
 * Part of UniDroid Suite: the launcher starts it as root from its own APK, which also
 * holds the web UI (assets/remote/).
 *   CLASSPATH=<apk> app_process /system/bin app.unidroid.remote.Server <port> <apk>
 */
public final class Server {
    static final String VERSION = "0.10.0";
    private static final String ASSETS = "assets/remote/";
    private static final File DEVICES = new File("/data/local/tmp/unidroid/devices.tsv");
    /** The remote's layout and custom buttons, shared by every phone. */
    private static final File LAYOUT = new File("/data/local/tmp/unidroid/layout.json");
    /** Apps and icons published by the launcher (AppExport), readable as root. */
    private static final File APPS = new File("/data/data/" + Actions.LAUNCHER + "/files/remote");
    private static final int LAYOUT_MAX = 32 * 1024;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 64 * 1024;

    private final String apk;
    private final String pageId;
    private final Map<String, byte[]> files;
    private final Injector injector;
    private final Actions actions;
    private final Devices devices = new Devices(DEVICES);
    /** Open WebSockets, to send the browser's state to every phone. */
    private final java.util.List<OutputStream> sockets = new CopyOnWriteArrayList<>();
    private final BrowserRelay browser = new BrowserRelay(this::broadcast);
    private final TvServer tv = new TvServer();
    private final VirtualMouse mouse = new VirtualMouse();
    private final Pin pin = new Pin();
    private final HdmiWatch hdmiWatch;
    private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private java.util.concurrent.ScheduledFuture<?> sleepTask;
    private volatile long sleepAt;

    private Server(String apk, Map<String, byte[]> files, Injector injector) {
        this.apk = apk;
        this.files = files;
        this.injector = injector;
        // Lets open pages notice that the app was updated and reload themselves.
        this.pageId = Integer.toHexString(java.util.Arrays.hashCode(files.get("index.html")));
        this.actions = new Actions(injector);
        this.hdmiWatch = new HdmiWatch(actions);
        hdmiWatch.start();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String apk = args.length > 1 ? args[1] : System.getProperty("java.class.path");
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // Not needed on every Android version.
        }
        Server server = new Server(apk, loadAssets(apk), new Injector());
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        System.out.println("UniDroid Remote " + VERSION + " on :" + port + ", from " + apk);
        while (true) {
            Socket client = socket.accept();
            Thread t = new Thread(() -> server.handle(client), "udr-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Web UI files, read once from the APK: an app update installs a new APK path anyway. */
    private static Map<String, byte[]> loadAssets(String apk) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(ASSETS)) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    out.put(e.getName().substring(ASSETS.length()), buf.toByteArray());
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(15_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            String wsKey = null, range = null, deviceHeader = null;
            long contentLength = -1;
            boolean upgrade = false;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("sec-websocket-key")) wsKey = value;
                else if (name.equals("content-length")) contentLength = parseLong(value);
                else if (name.equals("range")) range = value;
                else if (name.equals("x-udr-device")) deviceHeader = value;
                else if (name.equals("upgrade") && value.equalsIgnoreCase("websocket")) upgrade = true;
            }

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            if (path.equals("/ws") && upgrade && wsKey != null) {
                s.setSoTimeout(60_000); // the client pings every 5 s
                webSocket(in, out, wsKey);
                return;
            }
            boolean local = s.getInetAddress().isLoopbackAddress();
            // With a PIN set, phones must be trusted (or give the PIN) to control the projector.
            boolean allowed = local || pin.trusted(deviceHeader) || pin.admit(null, param(query, "pin"));
            if (!allowed && guarded(path)) {
                respond(out, 403, "text/plain", "pin", method.equals("HEAD"));
                return;
            }
            if (method.equals("POST") && path.equals("/upload")) {
                // A photo or video from a phone: store it, then show it in the browser.
                s.setSoTimeout(60_000);
                String name = Media.save(in, contentLength, param(query, "name"));
                if (name == null) {
                    respond(out, 400, "text/plain", "unsupported file", false);
                } else {
                    actions.run("url:http://127.0.0.1:" + s.getLocalPort() + "/view?f=" + name); // starts the browser if closed
                    respond(out, 200, "text/plain", name, false);
                }
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                respond(out, 405, "text/plain", "method not allowed", false);
                return;
            }
            boolean head = method.equals("HEAD");
            switch (path) {
                case "/key": {
                    int code = Injector.parseKeycode(param(query, "k"));
                    if (code < 0) {
                        respond(out, 400, "text/plain", "bad key", head);
                    } else {
                        injector.tap(code);
                        respond(out, 200, "text/plain", "ok", head);
                    }
                    break;
                }
                case "/text": {
                    String text = param(query, "t");
                    if (text == null || text.isEmpty()) {
                        respond(out, 400, "text/plain", "empty", head);
                    } else {
                        injector.type(text);
                        respond(out, 200, "text/plain", "ok", head);
                    }
                    break;
                }
                case "/action": {
                    String action = param(query, "a");
                    boolean ok = action != null && action(action);
                    respond(out, ok ? 200 : 400, "text/plain", ok ? "ok" : "bad action", head);
                    break;
                }
                case "/info":
                    respond(out, 200, "application/json", local ? localInfo() : info(), head);
                    break;
                case "/apps": {
                    byte[] list = readFile(new File(APPS, "apps.json"));
                    respond(out, 200, "application/json", list != null ? list : "[]".getBytes(StandardCharsets.UTF_8), head);
                    break;
                }
                case "/icon": {
                    String pkg = param(query, "p");
                    byte[] png = pkg != null && Actions.isPackageName(pkg) ? readFile(new File(APPS, "icons/" + pkg + ".png")) : null;
                    if (png == null) respond(out, 404, "text/plain", "no icon", head);
                    else respondCached(out, "image/png", png, head);
                    break;
                }
                case "/tv": {
                    // Picture and projection settings, for the launcher's panels (loopback only):
                    // /tv?t=<type>[&v=<value>], type = picture setting number or "mirror".
                    String body = local ? tv(param(query, "t"), param(query, "v")) : null;
                    respond(out, body != null ? 200 : 400, "text/plain", body != null ? body : "no", head);
                    break;
                }
                case "/screenshot": {
                    byte[] png = screenshot();
                    if (png == null) respond(out, 400, "text/plain", "no screenshot", head);
                    else respond(out, 200, "image/png", png, head);
                    break;
                }
                case "/view": {
                    String f = param(query, "f");
                    if (f == null || !f.matches("[0-9]+\\.[a-z0-9]{2,4}")) respond(out, 404, "text/plain", "no", head);
                    else respond(out, 200, "text/html; charset=utf-8", Media.viewer(f), head);
                    break;
                }
                case "/pin": {
                    // Set or clear the PIN: the launcher only (loopback).
                    boolean ok = local && (param(query, "clear") != null ? pin.set(null) : pin.set(param(query, "set")));
                    respond(out, ok ? 200 : 400, "text/plain", ok ? "ok" : "no", head);
                    break;
                }
                case "/forget": {
                    // Only the launcher, on the device itself, manages the device list.
                    boolean ok = local && devices.forget(param(query, "id"));
                    respond(out, ok ? 200 : 400, "text/plain", ok ? "ok" : "no", head);
                    break;
                }
                default:
                    if (path.startsWith("/media/")) Media.serve(out, path.substring(7), range, head);
                    else serveFile(out, path, head);
            }
        } catch (IOException ignored) {
            // Client went away.
        }
    }

    private void serveFile(OutputStream out, String path, boolean head) throws IOException {
        if (path.equals("/")) path = "/index.html";
        String name = URLDecoder.decode(path.substring(1), "UTF-8");
        byte[] body = files.get(name);
        if (body == null) {
            respond(out, 404, "text/plain", "not found", head);
            return;
        }
        respond(out, 200, contentType(name), body, head);
    }

    private static String contentType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "html": return "text/html; charset=utf-8";
            case "js": return "text/javascript; charset=utf-8";
            case "css": return "text/css; charset=utf-8";
            case "json": return "application/json";
            case "webmanifest": return "application/manifest+json";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            default: return "application/octet-stream";
        }
    }

    /** Reads or writes one setting; returns its value, or null when invalid or failed. */
    private String tv(String type, String value) {
        try {
            if ("mirror".equals(type)) {
                if (value == null) return String.valueOf(tv.getMirror());
                int v = Integer.parseInt(value);
                if (v < 0 || v > 3) return null;
                tv.setMirror(v);
                return String.valueOf(v);
            }
            int t = Integer.parseInt(type);
            if (!TvServer.isPicture(t)) return null;
            if (value == null) return String.valueOf(tv.getPicture(t));
            int v = Integer.parseInt(value);
            if (v < 0 || v > TvServer.max(t)) return null;
            tv.setPicture(t, v);
            return String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** Paths that act on the projector or show what is on it. */
    private static boolean guarded(String path) {
        switch (path) {
            case "/key": case "/text": case "/action": case "/upload": case "/screenshot": case "/apps": case "/view":
                return true;
            default:
                return path.startsWith("/media/");
        }
    }

    /** Named actions; sleep and auto HDMI are kept here because the server owns them. */
    private boolean action(String a) {
        if (a.startsWith("autohdmi:")) {
            hdmiWatch.set(a.endsWith("1"));
            return true;
        }
        if (a.startsWith("sleep:")) {
            try {
                sleepIn(Integer.parseInt(a.substring(6)));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return actions.run(a);
    }

    /**
     * Sleep timer: puts the projector to sleep after this many minutes (0 cancels). The
     * deadline goes to every phone ("z:<epoch ms>", 0 when off).
     */
    private synchronized void sleepIn(int minutes) {
        if (sleepTask != null) sleepTask.cancel(false);
        sleepTask = null;
        sleepAt = 0;
        if (minutes > 0 && minutes <= 600) {
            sleepAt = System.currentTimeMillis() + minutes * 60_000L;
            sleepTask = timer.schedule(() -> {
                synchronized (this) {
                    sleepAt = 0;
                    sleepTask = null;
                }
                injector.tap(223); // KEYCODE_SLEEP
                broadcast("z:0");
            }, minutes, java.util.concurrent.TimeUnit.MINUTES);
        }
        broadcast("z:" + sleepAt);
    }

    /** The current picture as PNG, taken as root. */
    private static byte[] screenshot() {
        try {
            Process p = new ProcessBuilder("screencap", "-p").start();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            try (InputStream in = p.getInputStream()) {
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            p.waitFor();
            return buf.size() > 0 ? buf.toByteArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** App icons change rarely: let the phone keep them for a day. */
    private static void respondCached(OutputStream out, String type, byte[] body, boolean head) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: max-age=86400\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(body);
        out.flush();
    }

    private static byte[] readFile(File f) {
        try {
            if (!f.isFile() || f.length() > 4 * 1024 * 1024) return null;
            return java.nio.file.Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    /** Saves a new layout from a phone and sends it to all the others. */
    private void saveLayout(String json) {
        if (json.length() > LAYOUT_MAX || !json.startsWith("{")) return;
        try {
            File tmp = new File(LAYOUT.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(LAYOUT)) return;
        } catch (IOException e) {
            return;
        }
        broadcast("l:" + json);
    }

    private static void respond(OutputStream out, int status, String type, String body, boolean head) throws IOException {
        respond(out, status, type, body.getBytes(StandardCharsets.UTF_8), head);
    }

    private static void respond(OutputStream out, int status, String type, byte[] body, boolean head) throws IOException {
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found"
                : status == 405 ? "Method Not Allowed" : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(body);
        out.flush();
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (IllegalArgumentException | IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String info() {
        return "{\"name\":\"" + json(Build.MODEL) + "\",\"maker\":\"" + json(Build.MANUFACTURER)
                + "\",\"android\":\"" + json(Build.VERSION.RELEASE) + "\",\"version\":\"" + VERSION + "\"}";
    }

    /** For the launcher (loopback only): also the APK in use and the known devices. */
    private String localInfo() {
        String base = info();
        return base.substring(0, base.length() - 1) + ",\"apk\":\"" + json(apk) + "\",\"devices\":" + devices.json()
                + ",\"pin\":" + pin.required() + ",\"autohdmi\":" + HdmiWatch.enabled() + ",\"sleepAt\":" + sleepAt + "}";
    }

    static String json(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Reads a CRLF-terminated header line byte by byte, so WebSocket frames are never over-read. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) return null;
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    // ----------------------------------------------------------- WebSocket
    //
    // Text messages, one per action:
    //   d:<key>  key down (auto-repeats while held)    u:<key>  key up
    //   k:<key>  tap (down + up)                       b:<n>    n backspaces
    //   t:<txt>  type text                             p        ping -> "p"
    //   a:<name> action: settings | apps | keystone | launch:<package>
    //   h:<id>\t<installed 0|1>\t<name>  first message: who is this page
    //   w:<command>  browser command, relayed to the UniDroid browser; its state comes back as w:<json>
    // <key> is a keycode number or name (22, DPAD_RIGHT, KEYCODE_DPAD_RIGHT).

    private void webSocket(InputStream in, OutputStream out, String key) throws IOException {
        String accept;
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            accept = Base64.encodeToString(sha1, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String hello = info();
        hello = hello.substring(0, hello.length() - 1) + ",\"page\":\"" + pageId + "\"}";
        sendFrame(out, 0x1, ("i:" + hello).getBytes(StandardCharsets.UTF_8));

        HeldKey held = new HeldKey(injector);
        Devices.Device[] device = new Devices.Device[1];
        boolean admitted = !pin.required();
        if (admitted) welcome(out);
        try {
            while (true) {
                int b0 = in.read();
                int b1 = in.read();
                if (b0 < 0 || b1 < 0) return;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (readByte(in) << 8) | readByte(in);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
                }
                if (len > MAX_FRAME) return;
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0) readFully(in, mask);
                byte[] payload = new byte[(int) len];
                readFully(in, payload);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                if (opcode == 0x8) {
                    sendFrame(out, 0x8, new byte[0]);
                    return;
                } else if (opcode == 0x9) {
                    sendFrame(out, 0xA, payload);
                } else if (opcode == 0x1) {
                    String msg = new String(payload, StandardCharsets.UTF_8);
                    if (!admitted) {
                        // Only the hello (with the PIN when needed) is read until the phone is let in.
                        if (!msg.startsWith("h:")) continue;
                        String[] h = msg.substring(2).split("\t", 4);
                        if (!pin.admit(h[0], h.length > 3 ? h[3] : null)) {
                            sendFrame(out, 0x1, "x:pin".getBytes(StandardCharsets.UTF_8));
                            continue;
                        }
                        admitted = true;
                        sendFrame(out, 0x1, "x:ok".getBytes(StandardCharsets.UTF_8));
                        welcome(out);
                    }
                    if (msg.startsWith("h:") && device[0] == null) device[0] = hello(msg.substring(2));
                    else if (msg.startsWith("w:")) browser.send(msg.substring(2));
                    else if (msg.startsWith("l:")) saveLayout(msg.substring(2));
                    else onMessage(msg, held, out);
                }
            }
        } finally {
            sockets.remove(out);
            held.releaseAll();
            devices.bye(device[0]);
        }
    }

    /** Layout, sleep timer and browser state for a phone that was just let in. */
    private void welcome(OutputStream out) throws IOException {
        byte[] layout = readFile(LAYOUT);
        if (layout != null) sendFrame(out, 0x1, ("l:" + new String(layout, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        sendFrame(out, 0x1, ("z:" + sleepAt).getBytes(StandardCharsets.UTF_8));
        String browserState = browser.last();
        if (browserState != null) sendFrame(out, 0x1, browserState.getBytes(StandardCharsets.UTF_8));
        sockets.add(out);
    }

    private void broadcast(String message) {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        for (OutputStream o : sockets) {
            try {
                sendFrame(o, 0x1, payload);
            } catch (IOException e) {
                sockets.remove(o);
            }
        }
    }

    /** h:<id>TAB<1 when opened from the home screen>TAB<name>[TAB<pin>] */
    private Devices.Device hello(String arg) {
        String[] p = arg.split("\t", 4);
        if (p.length < 3) return null;
        return devices.hello(p[0], p[2], "1".equals(p[1]));
    }

    /**
     * Mouse mode: "<dx> <dy>" moves, "s <v> <h>" scrolls (wheel notches), "c" left click,
     * "r" right click (Back in most apps), "d" / "u" left button down / up (drag).
     */
    private void mouse(String arg) {
        String[] p = arg.split(" ");
        try {
            switch (p[0]) {
                case "c": mouse.click(1); break;
                case "r": mouse.click(2); break;
                case "d": mouse.press(1, true); break;
                case "u": mouse.press(1, false); break;
                case "s": mouse.scroll(Integer.parseInt(p[1]), Integer.parseInt(p[2])); break;
                default: mouse.move(Math.round(Float.parseFloat(p[0])), Math.round(Float.parseFloat(p[1])));
            }
        } catch (RuntimeException ignored) {
            // Malformed message.
        }
    }

    private void onMessage(String msg, HeldKey held, OutputStream out) throws IOException {
        if (msg.equals("p")) {
            sendFrame(out, 0x1, new byte[] {'p'});
            return;
        }
        if (msg.length() < 3 || msg.charAt(1) != ':') return;
        String arg = msg.substring(2);
        switch (msg.charAt(0)) {
            case 't':
                injector.type(arg);
                return;
            case 'a':
                action(arg);
                return;
            case 'm':
                mouse(arg);
                return;
            case 'b': {
                int n;
                try {
                    n = Math.min(Integer.parseInt(arg), 500);
                } catch (NumberFormatException e) {
                    return;
                }
                for (int i = 0; i < n; i++) injector.tap(67); // KEYCODE_DEL
                return;
            }
            default:
                break;
        }
        int code = Injector.parseKeycode(arg);
        if (code < 0) return;
        switch (msg.charAt(0)) {
            case 'k': injector.tap(code); break;
            case 'd': held.press(code); break;
            case 'u': held.release(code); break;
            default: break;
        }
    }

    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        synchronized (out) {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write(i >= 4 ? 0 : (payload.length >>> (8 * i)) & 0xFF);
            }
            out.write(payload);
            out.flush();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }
}
