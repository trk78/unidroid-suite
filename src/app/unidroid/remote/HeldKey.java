package app.unidroid.remote;

import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Press-and-hold for one client: key down on press, auto-repeat while held
 * (400 ms delay, then every 50 ms like a physical remote), key up on release.
 */
final class HeldKey {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "udr-repeat");
        t.setDaemon(true);
        return t;
    });
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_EVERY_MS = 50;
    /** Safety net: a key is never held longer than this, even if the release is lost. */
    private static final long MAX_HOLD_MS = 15_000;

    private final Injector injector;
    private int code = -1;
    private long downTime;
    private int repeat;
    private ScheduledFuture<?> task;

    HeldKey(Injector injector) {
        this.injector = injector;
    }

    synchronized void press(int keycode) {
        releaseAll();
        code = keycode;
        downTime = SystemClock.uptimeMillis();
        repeat = 0;
        injector.key(KeyEvent.ACTION_DOWN, code, downTime, 0, 0);
        task = TIMER.scheduleAtFixedRate(this::tick, REPEAT_DELAY_MS, REPEAT_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    synchronized void release(int keycode) {
        if (code != keycode) return;
        if (task != null) task.cancel(false);
        injector.key(KeyEvent.ACTION_UP, code, downTime, 0, 0);
        code = -1;
    }

    synchronized void releaseAll() {
        if (code != -1) release(code);
    }

    private synchronized void tick() {
        if (code == -1) return;
        if (SystemClock.uptimeMillis() - downTime > MAX_HOLD_MS) {
            release(code);
            return;
        }
        repeat++;
        injector.key(KeyEvent.ACTION_DOWN, code, downTime, repeat, repeat == 1 ? KeyEvent.FLAG_LONG_PRESS : 0);
    }
}
