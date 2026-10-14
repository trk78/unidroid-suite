package app.unidroid.launcher;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs shell commands as root through {@code su}, off the main thread. Only used for
 * what Android does not expose to apps (projector geometry, forgetting saved networks).
 */
final class Root {
    interface Result {
        void done(boolean ok, String output);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Boolean available;

    private Root() {
    }

    /** Is a working {@code su} granted to us? Cached after the first check (which may prompt). */
    static void check(Result cb) {
        if (available != null) {
            boolean a = available;
            MAIN.post(() -> cb.done(a, ""));
            return;
        }
        run("id", (ok, out) -> {
            available = ok && out.contains("uid=0");
            cb.done(available, out);
        });
    }

    static void run(String command, Result cb) {
        POOL.execute(() -> {
            boolean ok;
            String out;
            try {
                Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                out = read(p.getInputStream());
                ok = p.waitFor() == 0;
            } catch (Exception e) {
                ok = false;
                out = String.valueOf(e.getMessage());
            }
            final boolean fOk = ok;
            final String fOut = out.trim();
            if (cb != null) MAIN.post(() -> cb.done(fOk, fOut));
        });
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
