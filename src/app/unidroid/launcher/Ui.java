package app.unidroid.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared palette and view helpers. Sizes are in units of a 1920-wide design grid. */
final class Ui {
    static final int INK = 0xFF05070C;
    static final int FG = 0xFFF4F6FB;
    static final int DIM = 0x9EDEE4F0;
    static final int GLASS = 0x14FFFFFF;
    static final int EDGE = 0x24FFFFFF;
    static final int SHEET = 0xF20B0E16;
    static final int GOOD = 0xFF3DDC84;
    static final int BAD = 0xFFFF6B6B;

    final Context ctx;
    final float unit;

    Ui(Context ctx) {
        this.ctx = ctx;
        unit = Math.max(ctx.getResources().getDisplayMetrics().widthPixels,
                ctx.getResources().getDisplayMetrics().heightPixels) / 1920f;
    }

    int px(float designUnits) {
        return Math.round(designUnits * unit);
    }

    TextView text(float size, int color, Typeface face) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable glass(int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(GLASS);
        d.setStroke(Math.max(1, px(1)), EDGE);
        return d;
    }

    LinearLayout.LayoutParams card(int w, int h, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), h < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(h));
        lp.rightMargin = px(gap);
        return lp;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static int darken(int color, float factor) {
        return Color.rgb((int) (Color.red(color) * factor), (int) (Color.green(color) * factor), (int) (Color.blue(color) * factor));
    }
}
