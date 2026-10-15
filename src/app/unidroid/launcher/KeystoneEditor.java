package app.unidroid.launcher;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen keystone editor, modelled on the vendor's manual correction screen
 * (Allwinner H713): a frame along the screen edges with a dot on each corner. OK picks
 * the next corner, arrows move it (faster while held), a long press on OK resets,
 * Back leaves. The whole output is warped by SurfaceFlinger, so the frame shows the
 * real edges of the projected picture.
 *
 * Each corner is an inward offset in thousandths of the screen (0-500). SurfaceFlinger
 * takes them as fractions through transaction 1050 and saves them itself, multiplied
 * by 1000, in persist.display.keystone_*.
 */
final class KeystoneEditor extends View {
    private static final String[] KEYS = {"lbx", "lby", "ltx", "lty", "rtx", "rty", "rbx", "rby"};
    private static final int MAX = 500;
    private static final int MAX_STEP = 8;
    private static final int SF_KEYSTONE = 1050;
    private static final int RESET_REPEATS = 10;
    /** Corner order when pressing OK (clockwise), as the index of its x in KEYS. */
    private static final int[] ORDER = {2, 4, 6, 0};

    private final Activity act;
    private final Ui ui;
    private final ViewGroup host;
    private final ViewGroup home;
    private final int accent;
    private final int[] v = new int[KEYS.length];
    private int corner;
    private int step;
    private boolean longPressed;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile OutputStream daemon;
    private final AtomicReference<String> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private KeystoneEditor(Activity act, Ui ui, ViewGroup host, ViewGroup home, int accent, int[] start) {
        super(act);
        this.act = act;
        this.ui = ui;
        this.host = host;
        this.home = home;
        this.accent = accent;
        System.arraycopy(start, 0, v, 0, v.length);
        setFocusable(true);
        setFocusableInTouchMode(true);
        line.setStyle(Paint.Style.STROKE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Reads the current corners as root, then shows the editor over the home screen. */
    static void open(Activity act, Ui ui, FrameLayout host, ViewGroup home, int accent, Runnable noRoot) {
        StringBuilder cmd = new StringBuilder();
        for (String k : KEYS) cmd.append("getprop persist.display.keystone_").append(k).append(';');
        Root.run(cmd.toString(), (ok, out) -> {
            String[] lines = out.split("\\s+");
            if (!ok || lines.length < KEYS.length) {
                noRoot.run();
                return;
            }
            int[] start = new int[KEYS.length];
            for (int i = 0; i < KEYS.length; i++) start[i] = parse(lines[i]);
            KeystoneEditor e = new KeystoneEditor(act, ui, host, home, accent, start);
            home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            host.addView(e, new FrameLayout.LayoutParams(-1, -1));
            e.requestFocus();
            e.openShell();
        });
    }

    /** Older firmwares stored the raw fraction ×1000 once more: bring anything above MAX back. */
    private static int parse(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            while (Math.abs(n) > MAX * 4) n /= 1000;
            return Math.max(0, Math.min(MAX, n));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == RESET_REPEATS) {
                    longPressed = true;
                    java.util.Arrays.fill(v, 0);
                    push();
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 1 per press, then faster while held (one more every 4 repeats, up to 8).
                step = Math.min(MAX_STEP, 1 + e.getRepeatCount() / 4);
                move(keyCode);
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!longPressed) {
                    corner = (corner + 1) % ORDER.length;
                    invalidate();
                }
                longPressed = false;
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                dismiss();
                return true;
            default:
                return super.onKeyUp(keyCode, e);
        }
    }

    /** Arrows move the corner on screen in their own direction; offsets grow inward. */
    private void move(int keyCode) {
        int x = ORDER[corner], y = x + 1;
        boolean left = x == 0 || x == 2, top = x == 2 || x == 4;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: v[x] += left ? step : -step; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: v[x] += left ? -step : step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: v[y] += top ? step : -step; break;
            case KeyEvent.KEYCODE_DPAD_UP: v[y] += top ? -step : step; break;
            default: return;
        }
        // Same limits as the vendor: 0-500, and a corner never crosses the one facing it
        // (left and right offsets on a row, top and bottom on a side, add up to 500 at most).
        int facingX = left ? x + 4 : x - 4;
        int facingY = y == 3 ? 1 : y == 1 ? 3 : y == 5 ? 7 : 5;
        v[x] = Math.max(0, Math.min(MAX - v[facingX], v[x]));
        v[y] = Math.max(0, Math.min(MAX - v[facingY], v[y]));
        push();
        invalidate();
    }

    // ------------------------------------------------------------------ root link

    /**
     * Starts {@link KeystoneDaemon} as root from this APK: each press is then one line on
     * its stdin and one Binder call. Falls back to {@code service call} if it cannot start.
     */
    private void openShell() {
        final String apk = act.getApplicationInfo().sourceDir;
        io.execute(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", "CLASSPATH=" + apk
                        + " exec app_process /system/bin " + KeystoneDaemon.class.getName())
                        .redirectErrorStream(true).start();
                BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
                if ("ready".equals(out.readLine())) {
                    daemon = p.getOutputStream();
                    // Keep reading so an error message can never fill the pipe and block it.
                    new Thread(() -> {
                        try {
                            while (out.readLine() != null) {
                                // Ignored.
                            }
                        } catch (Exception ignored) {
                            // Daemon gone.
                        }
                    }, "keystone-out").start();
                } else {
                    p.destroy();
                }
            } catch (Exception e) {
                daemon = null;
            }
        });
    }

    /**
     * Sends the corners. Only the latest position is kept, so holding an arrow can never
     * queue up moves that keep playing after the key is released.
     */
    private void push() {
        StringBuilder sb = new StringBuilder();
        for (int n : v) sb.append(String.format(Locale.ROOT, "%.3f ", n / 1000f));
        pending.set(sb.toString().trim());
        if (scheduled.compareAndSet(false, true)) io.execute(this::drain);
    }

    private void drain() {
        scheduled.set(false);
        String corners = pending.getAndSet(null);
        if (corners == null) return;
        OutputStream in = daemon;
        if (in != null) {
            try {
                in.write((corners + "\n").getBytes());
                in.flush();
                return;
            } catch (Exception e) {
                daemon = null;
            }
        }
        // Slow path, one call at a time: wait for it before taking the next position.
        final Object done = new Object();
        synchronized (done) {
            Root.run("service call SurfaceFlinger " + SF_KEYSTONE + " f " + corners.replace(" ", " f "), (ok, o) -> {
                synchronized (done) {
                    done.notify();
                }
            });
            try {
                done.wait(2000);
            } catch (InterruptedException ignored) {
                // Leaving.
            }
        }
    }

    /** Leaves the editor, keeping the corners as they are. */
    void dismiss() {
        io.execute(() -> {
            OutputStream in = daemon;
            daemon = null;
            try {
                // Closing stdin ends the daemon.
                if (in != null) in.close();
            } catch (Exception ignored) {
                // Already closed.
            }
        });
        io.shutdown();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        host.removeView(this);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Ui.INK);

        // Grid to line the picture up with the wall.
        line.setColor(Ui.withAlpha(Ui.FG, 26));
        line.setStrokeWidth(Math.max(1, ui.px(1)));
        for (int i = 1; i < 8; i++) {
            c.drawLine(w * i / 8f, 0, w * i / 8f, h, line);
            c.drawLine(0, h * i / 8f, w, h * i / 8f, line);
        }
        // Frame along the edges: it follows the projected picture.
        float stroke = ui.px(6);
        line.setColor(Ui.FG);
        line.setStrokeWidth(stroke);
        c.drawRect(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2, line);

        float r = ui.px(26), inset = ui.px(46);
        float[][] dots = {{inset, inset}, {w - inset, inset}, {w - inset, h - inset}, {inset, h - inset}};
        for (int i = 0; i < 4; i++) {
            boolean sel = i == corner;
            fill.setColor(sel ? accent : Ui.withAlpha(Ui.FG, 200));
            c.drawCircle(dots[i][0], dots[i][1], sel ? r * 1.25f : r, fill);
            if (sel) arrows(c, dots[i][0], dots[i][1], r * 2.4f);

            int x = ORDER[i];
            label.setTextSize(ui.px(30));
            label.setColor(sel ? Ui.FG : Ui.DIM);
            String txt = v[x] + " , " + v[x + 1];
            boolean right = i == 1 || i == 2, bottom = i >= 2;
            label.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
            float tx = right ? w - inset * 2.2f : inset * 2.2f;
            float ty = bottom ? h - inset * 2.2f : inset * 2.2f + ui.px(30);
            c.drawText(txt, tx, ty, label);
        }

        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(Ui.FG);
        label.setTextSize(ui.px(44));
        c.drawText(act.getString(R.string.keystone), w / 2f, h / 2f - ui.px(20), label);
        label.setTextSize(ui.px(28));
        label.setColor(Ui.DIM);
        c.drawText(act.getString(R.string.keystone_help), w / 2f, h / 2f + ui.px(40), label);
        c.drawText(act.getString(R.string.keystone_reset_help), w / 2f, h / 2f + ui.px(86), label);
    }

    /** Four small chevrons around the selected corner. */
    private void arrows(Canvas c, float cx, float cy, float d) {
        fill.setColor(accent);
        float s = ui.px(12);
        Path p = new Path();
        p.moveTo(cx, cy - d - s); p.lineTo(cx - s, cy - d + s * 0.4f); p.lineTo(cx + s, cy - d + s * 0.4f); p.close();
        p.moveTo(cx, cy + d + s); p.lineTo(cx - s, cy + d - s * 0.4f); p.lineTo(cx + s, cy + d - s * 0.4f); p.close();
        p.moveTo(cx - d - s, cy); p.lineTo(cx - d + s * 0.4f, cy - s); p.lineTo(cx - d + s * 0.4f, cy + s); p.close();
        p.moveTo(cx + d + s, cy); p.lineTo(cx + d - s * 0.4f, cy - s); p.lineTo(cx + d - s * 0.4f, cy + s); p.close();
        c.drawPath(p, fill);
    }
}
