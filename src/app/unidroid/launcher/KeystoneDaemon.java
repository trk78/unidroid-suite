package app.unidroid.launcher;

import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root helper started by {@link KeystoneEditor} through app_process, from this APK:
 * reads lines of eight corner fractions on stdin and hands them to SurfaceFlinger
 * (transaction 1050).
 *
 * SurfaceFlinger needs ~150 ms to apply each keystone, longer than the key repeat of a
 * remote. So stdin is read on one thread and only the latest line is applied on another:
 * positions skipped while SurfaceFlinger is busy are dropped instead of queued, and the
 * picture stops as soon as the key is released.
 */
public final class KeystoneDaemon {
    private static final int SF_KEYSTONE = 1050;
    private static final Object LOCK = new Object();
    private static String latest;
    private static boolean closed;

    private KeystoneDaemon() {
    }

    public static void main(String[] args) throws Exception {
        IBinder sf = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "SurfaceFlinger");
        if (sf == null) throw new IllegalStateException("SurfaceFlinger not found");

        Thread reader = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (LOCK) {
                        latest = line;
                        LOCK.notify();
                    }
                }
            } catch (Exception ignored) {
                // stdin closed: the editor is gone.
            }
            synchronized (LOCK) {
                closed = true;
                LOCK.notify();
            }
        }, "keystone-in");
        reader.start();
        System.out.println("ready");
        System.out.flush();

        while (true) {
            String line;
            synchronized (LOCK) {
                while (latest == null && !closed) LOCK.wait();
                if (latest == null) return;
                line = latest;
                latest = null;
            }
            apply(sf, line);
        }
    }

    private static void apply(IBinder sf, String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 8) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.ui.ISurfaceComposer");
            for (String p : parts) data.writeFloat(Float.parseFloat(p));
            sf.transact(SF_KEYSTONE, data, null, 0);
        } catch (Exception e) {
            System.err.println(e);
        } finally {
            data.recycle();
        }
    }
}
