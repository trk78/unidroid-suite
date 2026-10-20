package app.unidroid.launcher;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picture and projection settings, set by the root remote server through the vendor
 * service (/tv on the loopback). Calls run in order on one background thread.
 */
final class Tv {
    interface Values {
        /** One value per requested setting; -1 when it could not be read. */
        void got(int[] values);
    }

    static final String MIRROR = "mirror";
    static final String BRIGHTNESS = "1", CONTRAST = "2", SATURATION = "3", HUE = "4", SHARPNESS = "5", LIGHT = "6", COLOR_TEMP = "12";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Tv() {
    }

    static void read(String[] types, Values cb) {
        POOL.execute(() -> {
            int[] out = new int[types.length];
            for (int i = 0; i < types.length; i++) out[i] = parse(RemoteLink.get("/tv?t=" + types[i]));
            MAIN.post(() -> cb.got(out));
        });
    }

    static void write(String type, int value) {
        POOL.execute(() -> RemoteLink.get("/tv?t=" + type + "&v=" + value));
    }

    private static int parse(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
