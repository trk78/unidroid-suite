package app.unidroid.remote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phones and computers that opened the remote. Each page sends a random id it keeps in
 * its local storage, a short name and whether it runs from the home screen (installed
 * as a web app). The launcher shows a QR code until one device has installed it.
 * Saved as one line per device: id, name, installed, last seen.
 */
final class Devices {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    static final class Device {
        final String id;
        String name;
        boolean installed;
        long seen;
        int connections;

        Device(String id) {
            this.id = id;
        }
    }

    private final File file;
    private final Map<String, Device> byId = new LinkedHashMap<>();

    Devices(File file) {
        this.file = file;
        load();
    }

    /** A page said hello: remembers it and counts the open connection. */
    synchronized Device hello(String id, String name, boolean installed) {
        if (id == null || !ID.matcher(id).matches()) return null;
        Device d = byId.get(id);
        if (d == null) {
            d = new Device(id);
            byId.put(id, d);
        }
        d.name = clean(name);
        d.installed |= installed;
        d.seen = System.currentTimeMillis();
        d.connections++;
        save();
        return d;
    }

    synchronized void bye(Device d) {
        if (d == null) return;
        d.connections = Math.max(0, d.connections - 1);
        d.seen = System.currentTimeMillis();
        save();
    }

    synchronized boolean forget(String id) {
        boolean removed = byId.remove(id) != null;
        if (removed) save();
        return removed;
    }

    synchronized String json() {
        StringBuilder sb = new StringBuilder("[");
        for (Device d : byId.values()) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":\"").append(d.id).append("\",\"name\":\"").append(Server.json(d.name))
                    .append("\",\"installed\":").append(d.installed)
                    .append(",\"connected\":").append(d.connections > 0)
                    .append(",\"seen\":").append(d.seen).append('}');
        }
        return sb.append(']').toString();
    }

    private static String clean(String name) {
        if (name == null) return "Browser";
        String n = name.replaceAll("[\\t\\r\\n]", " ").trim();
        return n.isEmpty() ? "Browser" : n.substring(0, Math.min(40, n.length()));
    }

    private void load() {
        if (!file.isFile()) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 4 || !ID.matcher(p[0]).matches()) continue;
                Device d = new Device(p[0]);
                d.name = p[1];
                d.installed = "1".equals(p[2]);
                try {
                    d.seen = Long.parseLong(p[3]);
                } catch (NumberFormatException ignored) {
                    d.seen = 0;
                }
                byId.put(d.id, d);
            }
        } catch (Exception ignored) {
            // Start empty.
        }
    }

    private void save() {
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (Device d : byId.values()) {
                    w.write(d.id + "\t" + d.name + "\t" + (d.installed ? "1" : "0") + "\t" + d.seen + "\n");
                }
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (Exception ignored) {
            // Kept in memory only.
        }
    }
}
