package app.unidroid.launcher;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen background. Styles: the focused app's glow, a still halo, a still gradient,
 * two animated ones (aurora, nebula) and plain black, each in a colour palette.
 *
 * Animated styles only move and scale a few large blurred blobs drawn once into hardware
 * layers: the GPU composites them, nothing is redrawn per frame. That keeps them smooth on
 * a heat-throttled projector. Animations stop while the home screen is hidden.
 */
final class Backdrop extends FrameLayout {
    static final String GLOW = "dynamic", HALO = "accent", GRADIENT = "gradient", AURORA = "aurora", NEBULA = "nebula", PLAIN = "plain";
    static final String[] STYLES = {GLOW, AURORA, NEBULA, GRADIENT, HALO, PLAIN};

    /** Three colours each; "accent" is built from the accent colour. */
    static final String[] PALETTES = {"accent", "ocean", "dusk", "ember", "forest", "lavender"};
    private static final int[][] COLORS = {
        null,
        {0xFF1E6BFF, 0xFF00C2D1, 0xFF3A2DFF},
        {0xFF8F4BFF, 0xFFFF4F9A, 0xFF3B2A8F},
        {0xFFFF6A2B, 0xFFFF2D55, 0xFFFFB020},
        {0xFF1FBF75, 0xFF00A6A6, 0xFF7BD63A},
        {0xFFA98BFF, 0xFF6AA8FF, 0xFFE38BFF}
    };

    private final Ui ui;
    private final List<Animator> running = new ArrayList<>();
    private String style = GLOW;
    private int[] colors = COLORS[1];
    private int tint = 0xFF3D6FFF;
    private GradientDrawable glow;
    private ValueAnimator tintAnimator;

    Backdrop(Context ctx, Ui ui) {
        super(ctx);
        this.ui = ui;
    }

    static int[] palette(String name, int accent) {
        int i = Math.max(0, java.util.Arrays.asList(PALETTES).indexOf(name));
        if (i == 0) return new int[] {accent, Ui.darken(accent, 0.55f), shift(accent)};
        return COLORS[i];
    }

    /** A sibling colour for the accent palette: the accent with its channels rotated. */
    private static int shift(int c) {
        return 0xFF000000 | ((c & 0xFF) << 16) | (c >> 16 & 0xFF) << 8 | (c >> 8 & 0xFF);
    }

    void set(String style, int[] colors, int accent) {
        this.style = style;
        this.colors = colors;
        stop();
        removeAllViews();
        glow = null;
        switch (style) {
            case GLOW:
            case HALO:
                glow = blob(style.equals(HALO) ? accent : tint, 1500, 700, 150);
                place(glow, px(320), -px(640), Gravity.TOP | Gravity.END);
                break;
            case GRADIENT: {
                View v = new View(getContext());
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[] {Ui.withAlpha(colors[0], 120), Ui.withAlpha(colors[2], 60), Ui.withAlpha(colors[1], 110)});
                v.setBackground(g);
                addView(v, new LayoutParams(-1, -1));
                break;
            }
            case AURORA:
                // Three soft bands drifting slowly across the top half.
                drift(addBlob(colors[0], 1300, 600, 140, -px(200), -px(500)), 900, 260, 38_000);
                drift(addBlob(colors[1], 1100, 520, 120, px(700), -px(420)), -700, 220, 46_000);
                drift(addBlob(colors[2], 1000, 480, 110, px(300), -px(300)), 500, -200, 54_000);
                break;
            case NEBULA:
                // Two large clouds breathing in and out.
                breathe(addBlob(colors[0], 1400, 680, 130, px(700), -px(600)), 1.25f, 24_000);
                breathe(addBlob(colors[1], 1200, 600, 110, -px(300), px(100)), 1.35f, 31_000);
                break;
            default:
                break;
        }
        if (isShown()) start();
    }

    /** The focused item's colour (glow style only). */
    void tint(int target) {
        if (glow == null || !GLOW.equals(style)) return;
        if (tintAnimator != null) tintAnimator.cancel();
        tintAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), tint, target);
        tintAnimator.setDuration(900);
        tintAnimator.addUpdateListener(an -> {
            tint = (int) an.getAnimatedValue();
            if (glow != null) glow.setColors(new int[] {Ui.withAlpha(tint, 150), 0});
        });
        tintAnimator.start();
    }

    void start() {
        for (Animator a : running) if (!a.isStarted()) a.start();
        for (Animator a : running) if (a.isPaused()) a.resume();
    }

    void pause() {
        for (Animator a : running) a.pause();
    }

    private void stop() {
        for (Animator a : running) a.cancel();
        running.clear();
    }

    private int px(float u) {
        return ui.px(u);
    }

    private GradientDrawable blob(int color, int size, int radius, int alpha) {
        GradientDrawable d = new GradientDrawable();
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientRadius(px(radius));
        d.setColors(new int[] {Ui.withAlpha(color, alpha), 0});
        d.setSize(px(size), px(size));
        return d;
    }

    private View place(GradientDrawable d, int x, int y, int gravity) {
        View v = new View(getContext());
        v.setBackground(d);
        v.setLayerType(LAYER_TYPE_HARDWARE, null);
        v.setTranslationX(x);
        v.setTranslationY(y);
        addView(v, new LayoutParams(d.getIntrinsicWidth(), d.getIntrinsicHeight(), gravity));
        return v;
    }

    private View addBlob(int color, int size, int radius, int alpha, int x, int y) {
        return place(blob(color, size, radius, alpha), x, y, Gravity.TOP | Gravity.START);
    }

    private void drift(View v, int dx, int dy, long ms) {
        Animator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(TRANSLATION_X, v.getTranslationX(), v.getTranslationX() + px(dx)),
                PropertyValuesHolder.ofFloat(TRANSLATION_Y, v.getTranslationY(), v.getTranslationY() + px(dy)));
        loop(a, ms);
    }

    private void breathe(View v, float scale, long ms) {
        Animator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(SCALE_X, 1f, scale), PropertyValuesHolder.ofFloat(SCALE_Y, 1f, scale),
                PropertyValuesHolder.ofFloat(ALPHA, 0.75f, 1f));
        loop(a, ms);
    }

    private void loop(Animator a, long ms) {
        ObjectAnimator o = (ObjectAnimator) a;
        o.setDuration(ms);
        o.setRepeatCount(ValueAnimator.INFINITE);
        o.setRepeatMode(ValueAnimator.REVERSE);
        o.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        running.add(o);
    }
}
