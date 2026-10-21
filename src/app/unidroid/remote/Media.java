package app.unidroid.remote;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Photos and videos sent from a phone: stored for a while, then shown full screen in the
 * UniDroid browser through a small viewer page. The browser reads them from this server
 * (loopback), so no other app needs storage access. Only the last few files are kept.
 */
final class Media {
    static final File DIR = new File("/data/local/tmp/unidroid/media");
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int KEEP = 4;

    private Media() {
    }

    /** Stores an upload of exactly length bytes; returns the stored name, or null. */
    static String save(InputStream in, long length, String original) throws IOException {
        if (length <= 0 || length > MAX_BYTES) return null;
        String ext = extension(original);
        if (contentType(ext) == null) return null;
        if (!DIR.isDirectory() && !DIR.mkdirs()) return null;
        String name = System.currentTimeMillis() + "." + ext;
        File f = new File(DIR, name);
        byte[] buf = new byte[64 * 1024];
        long left = length;
        try (OutputStream out = new FileOutputStream(f)) {
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("short upload");
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            f.delete();
            throw e;
        }
        prune();
        return name;
    }

    static boolean isVideo(String name) {
        String t = contentType(extension(name));
        return t != null && t.startsWith("video/");
    }

    /** Full-screen black page showing one image, or playing one video. */
    static String viewer(String name) {
        String src = "/media/" + name;
        String body = isVideo(name)
                ? "<video src='" + src + "' autoplay controls playsinline></video>"
                : "<img src='" + src + "' alt=''>";
        return "<!doctype html><meta name=viewport content='width=device-width'><style>"
                + "html,body{margin:0;height:100%;background:#000}body{display:flex;align-items:center;justify-content:center}"
                + "img,video{width:100%;height:100%;object-fit:contain}</style>" + body;
    }

    /** Serves a stored file, with Range support so videos can seek. */
    static void serve(OutputStream out, String name, String range, boolean head) throws IOException {
        File f = name.matches("[0-9]+\\.[a-z0-9]{2,4}") ? new File(DIR, name) : null;
        String type = f != null ? contentType(extension(name)) : null;
        if (f == null || type == null || !f.isFile()) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return;
        }
        long size = f.length(), start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] r = range.substring(6).split("-", 2);
            try {
                if (!r[0].isEmpty()) start = Long.parseLong(r[0].trim());
                if (r.length > 1 && !r[1].trim().isEmpty()) end = Math.min(end, Long.parseLong(r[1].trim()));
                partial = start <= end && start < size;
            } catch (NumberFormatException ignored) {
                partial = false;
            }
            if (!partial) {
                start = 0;
                end = size - 1;
            }
        }
        long length = end - start + 1;
        String headers = (partial ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + start + "-" + end + "/" + size + "\r\n"
                : "HTTP/1.1 200 OK\r\n")
                + "Content-Type: " + type + "\r\nContent-Length: " + length + "\r\nAccept-Ranges: bytes\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!head) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[64 * 1024];
                while (length > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, length));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    length -= n;
                }
            }
        }
        out.flush();
    }

    private static void prune() {
        File[] files = DIR.listFiles();
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = KEEP; i < files.length; i++) files[i].delete();
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String contentType(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "mp4": case "m4v": return "video/mp4";
            case "mov": return "video/mp4"; // iPhone videos: H.264 in a QuickTime box plays as MP4
            case "webm": return "video/webm";
            default: return null;
        }
    }

}
