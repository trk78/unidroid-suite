package com.unidroid.remote;

import android.os.Build;
import android.os.Looper;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.Locale;

/**
 * UniDroid Remote server: serves the web UI and accepts commands over a WebSocket (/ws)
 * or plain HTTP GET (/key, /text, /action). Commands never go through a shell.
 *
 * Usage: CLASSPATH=unidroid-server.jar app_process / com.unidroid.remote.Server [port] [webroot]
 */
public final class Server {
    static final String VERSION = "0.3.0";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 64 * 1024;

    private final File webroot;
    private final Injector injector;
    private final Actions actions;

    private Server(File webroot, Injector injector) {
        this.webroot = webroot;
        this.injector = injector;
        this.actions = new Actions(injector);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        File webroot = new File(args.length > 1 ? args[1] : "webroot").getCanonicalFile();
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // Not needed on every Android version.
        }
        Server server = new Server(webroot, new Injector());
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        System.out.println("UniDroid Remote " + VERSION + " on :" + port + ", webroot " + webroot);
        while (true) {
            Socket client = socket.accept();
            Thread t = new Thread(() -> server.handle(client), "udr-conn");
            t.setDaemon(true);
            t.start();
        }
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

            String wsKey = null;
            boolean upgrade = false;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("sec-websocket-key")) wsKey = value;
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
                s.setSoTimeout(60_000); // the client pings every 20 s
                webSocket(in, out, wsKey);
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
                    boolean ok = action != null && actions.run(action);
                    respond(out, ok ? 200 : 400, "text/plain", ok ? "ok" : "bad action", head);
                    break;
                }
                case "/info":
                    respond(out, 200, "application/json", info(), head);
                    break;
                default:
                    serveFile(out, path, head);
            }
        } catch (IOException ignored) {
            // Client went away.
        }
    }

    private void serveFile(OutputStream out, String path, boolean head) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || path.indexOf('\0') >= 0) {
            respond(out, 400, "text/plain", "bad path", head);
            return;
        }
        File f = new File(webroot, URLDecoder.decode(path.substring(1), "UTF-8")).getCanonicalFile();
        if (!f.getPath().startsWith(webroot.getPath() + File.separator) || !f.isFile()) {
            respond(out, 404, "text/plain", "not found", head);
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream((int) f.length());
        try (FileInputStream fin = new FileInputStream(f)) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = fin.read(chunk)) > 0) buf.write(chunk, 0, n);
        }
        respond(out, 200, contentType(f.getName()), buf.toByteArray(), head);
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

    private static void respond(OutputStream out, int status, String type, String body, boolean head) throws IOException {
        respond(out, status, type, body.getBytes(StandardCharsets.UTF_8), head);
    }

    private static void respond(OutputStream out, int status, String type, byte[] body, boolean head) throws IOException {
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : "Bad Request";
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

    private static String json(String s) {
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
    //   a:<name> action: settings | apps | launch:<package>
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
        sendFrame(out, 0x1, ("i:" + info()).getBytes(StandardCharsets.UTF_8));

        HeldKey held = new HeldKey(injector);
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
                    onMessage(new String(payload, StandardCharsets.UTF_8), held, out);
                }
            }
        } finally {
            held.releaseAll();
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
                actions.run(arg);
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
            } else {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length & 0xFF);
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
