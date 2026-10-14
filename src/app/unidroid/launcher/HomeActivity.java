package app.unidroid.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen: clock, a hero line describing the focused item, then rows of
 * favorites, projector shortcuts and all apps. Everything is laid out on a
 * 1920-unit grid so it looks the same on 720p and 1080p screens.
 */
public final class HomeActivity extends Activity {
    private static final int INK = 0xFF05070C;
    private static final int FG = 0xFFF4F6FB;
    private static final int DIM = 0x9EDEE4F0;
    private static final int GLASS = 0x14FFFFFF;
    private static final int EDGE = 0x24FFFFFF;
    private static final int ACCENT = 0xFF6AA8FF;
    private static final int REMOTE_PORT = 8080;

    private float unit;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<AppRepo.App> apps = new ArrayList<>();

    private GradientDrawable aurora;
    private int tint = 0xFF3D6FFF;
    private ValueAnimator tintAnimator;
    private TextView clock, date, kicker, title, subtitle;
    private ScrollView scroll;
    private LinearLayout favoritesRow, projectorRow, appsRow;
    private TextView remoteValue;
    private String remoteUrl;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        unit = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) / 1920f;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        setContentView(buildRoot());
        reload();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageChanges, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(clockTick);
        clockTick.run();
        probeRemote();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageChanges);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if ("apps".equals(intent.getStringExtra("open"))) {
            focusFirst(appsRow);
        } else {
            // HOME pressed while already home: back to the top.
            scroll.smoothScrollTo(0, 0);
            focusFirst(favoritesRow);
        }
    }

    @Override
    public void onBackPressed() {
        // This is the home screen: there is nothing to go back to.
    }

    // ------------------------------------------------------------------ layout

    private int px(float designUnits) {
        return Math.round(designUnits * unit);
    }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(INK);

        // Dynamic wallpaper: a large soft glow tinted with the focused app's colour.
        View glow = new View(this);
        aurora = new GradientDrawable();
        aurora.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        aurora.setGradientRadius(px(700));
        aurora.setColors(new int[] {withAlpha(tint, 150), 0});
        glow.setBackground(aurora);
        FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(px(1500), px(1500), Gravity.TOP | Gravity.END);
        glow.setTranslationX(px(320));
        glow.setTranslationY(-px(640));
        root.addView(glow, glowLp);
        root.addView(scrim(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {withAlpha(INK, 235), withAlpha(INK, 90), 0}));
        root.addView(scrim(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {withAlpha(INK, 240), withAlpha(INK, 50), 0}));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(px(110), px(56), 0, px(80));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        column.addView(buildTopBar());
        column.addView(buildHero());
        favoritesRow = addRow(column, R.string.row_favorites);
        projectorRow = addRow(column, R.string.row_projector);
        appsRow = addRow(column, R.string.row_apps);
        return root;
    }

    private View scrim(GradientDrawable.Orientation orientation, int[] colors) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(orientation, colors));
        return v;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 0, px(96), 0);
        bar.setClipChildren(false);

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        clock = text(92, FG, Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setLetterSpacing(-0.03f);
        date = text(26, DIM, Typeface.DEFAULT);
        time.addView(clock);
        time.addView(date);
        bar.addView(time, new LinearLayout.LayoutParams(0, -2, 1));

        bar.addView(statusButton(R.drawable.ic_wifi, R.string.wifi, R.string.wifi_sub, Settings.ACTION_WIFI_SETTINGS));
        bar.addView(statusButton(R.drawable.ic_bluetooth, R.string.bluetooth, R.string.bluetooth_sub, Settings.ACTION_BLUETOOTH_SETTINGS));
        bar.addView(statusButton(R.drawable.ic_settings, R.string.settings, R.string.settings_sub, Settings.ACTION_SETTINGS));
        return bar;
    }

    private View statusButton(int icon, int label, int sub, String action) {
        FrameLayout b = new FrameLayout(this);
        GradientDrawable bg = glassShape(px(32));
        b.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(FG);
        b.addView(iv, new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(64), px(64));
        lp.leftMargin = px(16);
        lp.topMargin = px(10);
        b.setLayoutParams(lp);
        makeFocusable(b, 1.12f, getString(R.string.kicker_system), getString(label), getString(sub), ACCENT, focused -> {
            bg.setColor(focused ? FG : GLASS);
            iv.setColorFilter(focused ? INK : FG);
        });
        b.setOnClickListener(v -> start(new Intent(action), getString(label)));
        return b;
    }

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(0, px(56), 0, 0);
        kicker = text(22, ACCENT, Typeface.DEFAULT_BOLD);
        kicker.setLetterSpacing(0.14f);
        kicker.setAllCaps(true);
        title = text(84, FG, Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.03f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = text(28, DIM, Typeface.DEFAULT);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(kicker);
        hero.addView(title);
        hero.addView(subtitle);
        hero.setLayoutParams(new LinearLayout.LayoutParams(px(1500), px(270)));
        return hero;
    }

    private LinearLayout addRow(LinearLayout column, int heading) {
        TextView h = text(26, DIM, Typeface.DEFAULT_BOLD);
        h.setText(heading);
        h.setPadding(px(24), px(18), 0, 0);
        column.addView(h);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFocusable(false);
        hs.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(px(24), px(26), px(96), px(34));
        hs.addView(row);
        column.addView(hs);
        return row;
    }

    // ------------------------------------------------------------------ content

    private void reload() {
        apps = AppRepo.load(this);
        List<AppRepo.App> favorites = AppRepo.favorites(prefs, apps);
        View focused = getCurrentFocus();

        favoritesRow.removeAllViews();
        for (AppRepo.App a : favorites) favoritesRow.addView(favoriteCard(a));
        favoritesRow.addView(addFavoriteCard());

        projectorRow.removeAllViews();
        addTile(projectorRow, R.drawable.ic_projector, R.string.projection, R.string.projection_sub,
                new Intent("action.htcsettings.prjsettings"), 0xFF8F6BFF);
        addTile(projectorRow, R.drawable.ic_wifi, R.string.wifi, R.string.wifi_sub,
                new Intent(Settings.ACTION_WIFI_SETTINGS), 0xFF4CC4FF);
        addTile(projectorRow, R.drawable.ic_bluetooth, R.string.bluetooth, R.string.bluetooth_sub,
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0xFF5B7CFF);
        projectorRow.addView(remoteTile());

        appsRow.removeAllViews();
        for (AppRepo.App a : apps) appsRow.addView(appIcon(a));

        if (focused == null || !focused.isAttachedToWindow()) main.post(() -> focusFirst(favoritesRow));
    }

    private View favoriteCard(AppRepo.App a) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {a.color, darken(a.color, 0.45f)});
        bg.setCornerRadius(px(20));
        card.setBackground(bg);
        card.setClipToOutline(true);
        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.banner);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            inner.addView(iv, new LinearLayout.LayoutParams(px(88), px(88)));
            TextView label = text(26, Color.WHITE, Typeface.DEFAULT_BOLD);
            label.setText(a.label);
            label.setSingleLine(true);
            label.setPadding(px(16), px(10), px(16), 0);
            inner.addView(label);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setLayoutParams(cardParams(352, 198, 30));
        card.setAlpha(0.8f);
        makeFocusable(card, 1.1f, getString(R.string.kicker_favorite), a.label, a.category(this), a.color, focused -> {
            card.setAlpha(focused ? 1f : 0.8f);
            setRing(card, focused, 20);
        });
        card.setOnClickListener(v -> start(a.launchIntent(), a.label));
        card.setOnLongClickListener(v -> {
            toggleFavorite(a);
            return true;
        });
        return card;
    }

    private View addFavoriteCard() {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = glassShape(px(20));
        card.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_add);
        iv.setColorFilter(DIM);
        card.addView(iv, new FrameLayout.LayoutParams(px(56), px(56), Gravity.CENTER));
        card.setLayoutParams(cardParams(198, 198, 30));
        makeFocusable(card, 1.1f, getString(R.string.kicker_customize), getString(R.string.add_favorite),
                getString(R.string.add_favorite_sub), 0xFF7A86A8, focused -> {
                    bg.setColor(focused ? FG : GLASS);
                    iv.setColorFilter(focused ? INK : DIM);
                });
        card.setOnClickListener(v -> {
            focusFirst(appsRow);
            toast(getString(R.string.hold_to_favorite));
        });
        return card;
    }

    private void addTile(LinearLayout row, int icon, int label, int sub, Intent intent, int color) {
        if (intent.resolveActivity(getPackageManager()) == null) return; // not on this device
        row.addView(tile(icon, getString(label), null, getString(sub), color, v -> start(intent, getString(label)), 220));
    }

    private View remoteTile() {
        View t = tile(R.drawable.ic_remote, getString(R.string.remote), getString(R.string.remote_off),
                getString(R.string.remote_off_sub), 0xFF0A84FF, v -> {
                    if (remoteUrl != null) toast(remoteUrl);
                    else toast(getString(R.string.remote_off_sub));
                }, 420);
        remoteValue = (TextView) t.findViewWithTag("value");
        return t;
    }

    private View tile(int icon, String label, String value, String sub, int color, View.OnClickListener click, int width) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(px(24), px(22), px(24), px(20));
        GradientDrawable bg = glassShape(px(22));
        t.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(FG);
        t.addView(iv, new LinearLayout.LayoutParams(px(34), px(34)));
        View spacer = new View(this);
        t.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1));
        TextView name = text(24, FG, Typeface.DEFAULT_BOLD);
        name.setText(label);
        t.addView(name);
        TextView val = null;
        if (value != null) {
            val = text(19, DIM, Typeface.DEFAULT);
            val.setText(value);
            val.setTag("value");
            val.setSingleLine(true);
            t.addView(val);
        }
        final TextView valueView = val;
        t.setLayoutParams(cardParams(width, 132, 30));
        makeFocusable(t, 1.1f, getString(R.string.kicker_system), label, sub, color, focused -> {
            bg.setColor(focused ? FG : GLASS);
            iv.setColorFilter(focused ? INK : FG);
            name.setTextColor(focused ? INK : FG);
            if (valueView != null) valueView.setTextColor(focused ? withAlpha(INK, 170) : DIM);
            if (focused && valueView != null) {
                subtitle.setText(remoteUrl != null ? R.string.remote_on_sub : R.string.remote_off_sub);
            }
        });
        t.setOnClickListener(click);
        return t;
    }

    private View appIcon(AppRepo.App a) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = glassShape(px(34));
        box.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        box.addView(iv, new FrameLayout.LayoutParams(px(84), px(84), Gravity.CENTER));
        col.addView(box, new LinearLayout.LayoutParams(px(132), px(132)));
        TextView label = text(21, DIM, Typeface.DEFAULT);
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(px(170), -2);
        llp.topMargin = px(14);
        col.addView(label, llp);
        col.setLayoutParams(cardParams(170, -1, 14));
        makeFocusable(col, 1.1f, getString(R.string.kicker_app), a.label, a.category(this), a.color, focused -> {
            bg.setColor(focused ? FG : GLASS);
            label.setTextColor(focused ? FG : DIM);
        });
        col.setOnClickListener(v -> start(a.launchIntent(), a.label));
        col.setOnLongClickListener(v -> {
            toggleFavorite(a);
            return true;
        });
        return col;
    }

    // ------------------------------------------------------------------ behaviour

    private interface FocusStyle {
        void apply(boolean focused);
    }

    private void makeFocusable(View v, float scale, String kick, String head, String sub, int color, FocusStyle style) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, focused) -> {
            view.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f)
                    .translationZ(focused ? px(24) : 0).setDuration(240)
                    .setInterpolator(new OvershootInterpolator(1.4f)).start();
            if (focused) {
                kicker.setText(kick);
                title.setText(head);
                subtitle.setText(sub);
                setTint(color);
            }
            style.apply(focused);
        });
    }

    private void setRing(View v, boolean on, int radius) {
        if (Build.VERSION.SDK_INT < 23) return;
        if (!on) {
            v.setForeground(null);
            return;
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(radius));
        ring.setStroke(px(3), withAlpha(Color.WHITE, 235));
        v.setForeground(ring);
    }

    private void setTint(int target) {
        if (tintAnimator != null) tintAnimator.cancel();
        tintAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), tint, target);
        tintAnimator.setDuration(900);
        tintAnimator.addUpdateListener(an -> {
            tint = (int) an.getAnimatedValue();
            aurora.setColors(new int[] {withAlpha(tint, 150), 0});
        });
        tintAnimator.start();
    }

    private void toggleFavorite(AppRepo.App a) {
        boolean added = AppRepo.toggleFavorite(prefs, apps, a);
        toast(getString(added ? R.string.fav_added : R.string.fav_removed, a.label));
        reload();
    }

    private void start(Intent intent, String label) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast(getString(R.string.launch_failed, label));
        }
    }

    private void focusFirst(LinearLayout row) {
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    private void updateClock() {
        Date now = new Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        String d = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now);
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

    /** Is UniDroid Remote answering on this device? Shows its address when it is. */
    private void probeRemote() {
        new Thread(() -> {
            String url = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + REMOTE_PORT + "/info").openConnection();
                c.setConnectTimeout(600);
                c.setReadTimeout(600);
                if (c.getResponseCode() == 200) url = "http://" + lanAddress() + ":" + REMOTE_PORT;
                c.disconnect();
            } catch (Exception ignored) {
                // Not installed or not running.
            }
            final String found = url;
            main.post(() -> {
                remoteUrl = found;
                if (remoteValue != null) {
                    remoteValue.setText(found != null ? found.substring(7) : getString(R.string.remote_off));
                }
            });
        }, "remote-probe").start();
    }

    private static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return "127.0.0.1";
    }

    // ------------------------------------------------------------------ helpers

    private TextView text(float size, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable glassShape(int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(GLASS);
        d.setStroke(Math.max(1, px(1)), EDGE);
        return d;
    }

    private LinearLayout.LayoutParams cardParams(int w, int h, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), h < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(h));
        lp.rightMargin = px(gap);
        return lp;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int darken(int color, float factor) {
        return Color.rgb((int) (Color.red(color) * factor), (int) (Color.green(color) * factor), (int) (Color.blue(color) * factor));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
