package app.unidroid.launcher;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A side panel that slides in from the right over the home screen, in the style of
 * the Google TV quick settings: a header and a focusable list of rows. Panels
 * (Wi-Fi, Bluetooth, projection, settings, app menu) fill it through this API.
 */
final class Sheet {
    interface Toggle {
        void changed(boolean on);
    }

    interface Choice {
        void picked(int index);
    }

    /** A row whose texts can be updated after it is shown. */
    final class Row {
        final LinearLayout view;
        final TextView label;
        final TextView value;
        final TextView trailing;

        Row(LinearLayout view, TextView label, TextView value, TextView trailing) {
            this.view = view;
            this.label = label;
            this.value = value;
            this.trailing = trailing;
        }

        Row value(CharSequence v) {
            value.setText(v);
            value.setVisibility(TextUtils.isEmpty(v) ? View.GONE : View.VISIBLE);
            return this;
        }

        Row trailing(CharSequence t) {
            trailing.setText(t);
            trailing.setVisibility(TextUtils.isEmpty(t) ? View.GONE : View.VISIBLE);
            return this;
        }
    }

    private final Ui ui;
    private final FrameLayout host;
    private final ViewGroup home;
    private FrameLayout overlay;
    private LinearLayout panel;
    private LinearLayout list;
    private ScrollView scroll;
    private ImageView headIcon;
    private TextView headTitle, headSub;
    private View returnFocus;
    private Runnable onClose;
    private int accent;

    Sheet(Ui ui, FrameLayout host, ViewGroup home) {
        this.ui = ui;
        this.host = host;
        this.home = home;
    }

    boolean isOpen() {
        return overlay != null;
    }

    /** Opens (or re-titles) the panel and empties its list. */
    Sheet open(int icon, String title, String subtitle, int accentColor, Runnable onClose) {
        accent = accentColor;
        if (overlay == null) build();
        else if (this.onClose != null) this.onClose.run();
        this.onClose = onClose;
        if (icon != 0) {
            headIcon.setImageResource(icon);
            headIcon.setColorFilter(accent);
        }
        headTitle.setText(title);
        headSub.setText(subtitle);
        headSub.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        list.removeAllViews();
        scroll.scrollTo(0, 0);
        return this;
    }

    /** Same as {@link #open(int, String, String, int, Runnable)} with an app icon in the header. */
    Sheet open(android.graphics.drawable.Drawable icon, String title, String subtitle, int accentColor, Runnable onClose) {
        open(0, title, subtitle, accentColor, onClose);
        headIcon.clearColorFilter();
        headIcon.setImageDrawable(icon);
        return this;
    }

    void subtitle(CharSequence s) {
        if (headSub == null) return;
        headSub.setText(s);
        headSub.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
    }

    void clear() {
        list.removeAllViews();
    }

    /** Puts focus on the first row; call after filling the list. */
    void focus() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
    }

    /** Index of the focused row in the list, or -1. Used to keep focus across a refresh. */
    int focusedIndex() {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).hasFocus()) return i;
        }
        return -1;
    }

    /** Focuses the row at this index, or the nearest focusable one before it. */
    void focusAt(int index) {
        for (int i = Math.min(index, list.getChildCount() - 1); i >= 0; i--) {
            View v = list.getChildAt(i);
            if (v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
        focus();
    }

    void close() {
        if (overlay == null) return;
        if (onClose != null) onClose.run();
        onClose = null;
        final FrameLayout o = overlay;
        overlay = null;
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        panel.animate().translationX(ui.px(780)).setDuration(180).start();
        o.animate().alpha(0f).setDuration(200).withEndAction(() -> host.removeView(o)).start();
        if (returnFocus != null && returnFocus.isAttachedToWindow()) returnFocus.requestFocus();
    }

    /** Back / Escape close the panel. Returns true when the key was used. */
    boolean onBack() {
        if (overlay == null) return false;
        close();
        return true;
    }

    // ------------------------------------------------------------------ rows

    TextView section(String heading) {
        TextView h = ui.text(21, Ui.DIM, Typeface.DEFAULT_BOLD);
        h.setText(heading);
        h.setAllCaps(true);
        h.setLetterSpacing(0.12f);
        h.setPadding(ui.px(8), ui.px(26), 0, ui.px(10));
        list.addView(h);
        return h;
    }

    TextView note(CharSequence text) {
        TextView t = ui.text(21, Ui.DIM, Typeface.DEFAULT);
        t.setText(text);
        t.setLineSpacing(ui.px(6), 1f);
        t.setPadding(ui.px(8), ui.px(10), ui.px(8), ui.px(10));
        list.addView(t);
        return t;
    }

    Row item(int icon, String label, CharSequence value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ui.ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.px(92));
        row.setPadding(ui.px(24), ui.px(16), ui.px(24), ui.px(16));
        GradientDrawable bg = ui.glass(ui.px(18));
        row.setBackground(bg);

        ImageView iv = null;
        if (icon != 0) {
            iv = new ImageView(ui.ctx);
            iv.setImageResource(icon);
            iv.setColorFilter(Ui.FG);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.px(32), ui.px(32));
            ilp.rightMargin = ui.px(22);
            row.addView(iv, ilp);
        }
        LinearLayout texts = new LinearLayout(ui.ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(26, Ui.FG, Typeface.DEFAULT_BOLD);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView val = ui.text(20, Ui.DIM, Typeface.DEFAULT);
        val.setPadding(0, ui.px(6), 0, 0);
        val.setMaxLines(2);
        val.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(val);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView trail = ui.text(21, Ui.DIM, Typeface.DEFAULT_BOLD);
        trail.setPadding(ui.px(16), 0, 0, 0);
        row.addView(trail);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.px(12);
        list.addView(row, lp);

        final ImageView icv = iv;
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? Ui.FG : Ui.GLASS);
            name.setTextColor(focused ? Ui.INK : Ui.FG);
            val.setTextColor(focused ? Ui.withAlpha(Ui.INK, 170) : Ui.DIM);
            trail.setTextColor(focused ? Ui.withAlpha(Ui.INK, 200) : (trail.getTag() instanceof Integer ? (Integer) trail.getTag() : Ui.DIM));
            if (icv != null) icv.setColorFilter(focused ? Ui.INK : Ui.FG);
        });
        if (click != null) row.setOnClickListener(click);
        Row r = new Row(row, name, val, trail);
        r.value(value);
        r.trailing(null);
        return r;
    }

    Row toggle(int icon, String label, String sub, boolean on, Toggle change) {
        final boolean[] state = {on};
        final Row[] holder = new Row[1];
        Row r = item(icon, label, sub, v -> {
            state[0] = !state[0];
            paintToggle(holder[0], state[0]);
            change.changed(state[0]);
        });
        holder[0] = r;
        paintToggle(r, on);
        return r;
    }

    void paintToggle(Row r, boolean on) {
        r.trailing.setTag(on ? Ui.GOOD : Ui.DIM);
        if (!r.view.isFocused()) r.trailing.setTextColor(on ? Ui.GOOD : Ui.DIM);
        r.trailing(ui.ctx.getString(on ? R.string.on : R.string.off));
    }

    /** A row that cycles through options with OK, Left and Right. */
    Row choice(int icon, String label, String[] options, int index, Choice pick) {
        final int[] at = {Math.max(0, Math.min(index, options.length - 1))};
        final Row[] holder = new Row[1];
        Row r = item(icon, label, null, v -> {
            at[0] = (at[0] + 1) % options.length;
            holder[0].trailing("‹ " + options[at[0]] + " ›");
            pick.picked(at[0]);
        });
        holder[0] = r;
        r.trailing("‹ " + options[at[0]] + " ›");
        r.view.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (step == 0) return false;
            at[0] = (at[0] + step + options.length) % options.length;
            r.trailing("‹ " + options[at[0]] + " ›");
            pick.picked(at[0]);
            return true;
        });
        return r;
    }

    /** Adds any view to the list (used for the Wi-Fi password field). */
    void custom(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.px(12);
        list.addView(v, lp);
    }

    // ------------------------------------------------------------------ build

    private void build() {
        returnFocus = home.findFocus();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        overlay = new FrameLayout(ui.ctx);
        overlay.setBackgroundColor(Ui.withAlpha(Ui.INK, 150));
        overlay.setClickable(true);

        panel = new LinearLayout(ui.ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Ui.SHEET);
        panel.setPadding(ui.px(52), ui.px(56), ui.px(52), ui.px(HomeActivity.SAFE_EDGE));
        overlay.addView(panel, new FrameLayout.LayoutParams(ui.px(780), -1, Gravity.END));

        headIcon = new ImageView(ui.ctx);
        panel.addView(headIcon, new LinearLayout.LayoutParams(ui.px(44), ui.px(44)));
        headTitle = ui.text(52, Ui.FG, Typeface.DEFAULT_BOLD);
        headTitle.setPadding(0, ui.px(20), 0, 0);
        headTitle.setSingleLine(true);
        headTitle.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(headTitle);
        headSub = ui.text(23, Ui.DIM, Typeface.DEFAULT);
        headSub.setPadding(0, ui.px(10), 0, 0);
        headSub.setLineSpacing(ui.px(4), 1f);
        panel.addView(headSub);

        scroll = new ScrollView(ui.ctx);
        scroll.setVerticalScrollBarEnabled(false);
        // Rows scroll under a soft edge and stop short of the screen bottom, where the
        // keystone crops the picture; they never slide under the header.
        scroll.setPadding(0, ui.px(12), 0, 0);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(ui.px(48));
        list = new LinearLayout(ui.ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(200).start();
        panel.setTranslationX(ui.px(780));
        panel.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
    }
}
