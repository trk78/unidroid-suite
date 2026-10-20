package app.unidroid.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The home screen: clock, a hero line describing the focused item, then rows of
 * favorites, projector shortcuts and all apps. Everything is laid out on a
 * 1920-unit grid so it looks the same on 720p and 1080p screens. Wi-Fi, Bluetooth,
 * projection and settings open as side panels ({@link Sheet}) over this screen.
 */
public final class HomeActivity extends Activity implements SettingsPanel.Host {
    /** Margin kept free of text at the screen edges, in design units (5 % of 1080). */
    static final int SAFE_EDGE = 54;
    /** Where the hero text rests once the home screen has scrolled down, in design units. */
    private static final int HERO_EDGE = 84;

    private Ui ui;
    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<AppRepo.App> apps = new ArrayList<>();

    private View glow;
    private GradientDrawable aurora;
    private int tint = 0xFF3D6FFF;
    private ValueAnimator tintAnimator;
    private View timeBlock, hero;
    private TextView clock, date, kicker, title, subtitle;
    private ScrollView scroll;
    private LinearLayout favoritesRow, projectorRow, appsRow;
    private View projectorSection, appsSection;
    private RemoteLink.State remoteState;
    private View remoteTileView;
    private boolean remoteTileQr;
    private QrView remoteQr;
    private TextView remoteLine1, remoteLine2;

    private Sheet sheet;
    private WifiPanel wifiPanel;
    private BluetoothPanel bluetoothPanel;
    private ProjectionPanel projectionPanel;
    private SettingsPanel settingsPanel;
    private RemotePanel remotePanel;
    private PicturePanel picturePanel;
    private SoundPanel soundPanel;

    /** Keeps the remote server running and the remote tile up to date while home is shown. */
    private final Runnable remotePoll = new Runnable() {
        @Override
        public void run() {
            RemoteLink.refresh(HomeActivity.this, true, s -> {
                remoteState = s;
                renderRemoteTile();
            });
            main.postDelayed(this, 5_000);
        }
    };

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
        ui = new Ui(this);
        prefs = new Prefs(getSharedPreferences("launcher", MODE_PRIVATE));
        FrameLayout root = buildRoot();
        setContentView(root);
        sheet = new Sheet(ui, root, scroll);
        wifiPanel = new WifiPanel(this, ui, sheet);
        bluetoothPanel = new BluetoothPanel(this, sheet);
        projectionPanel = new ProjectionPanel(this, ui, sheet, root, scroll, prefs);
        settingsPanel = new SettingsPanel(this, sheet, prefs, this);
        picturePanel = new PicturePanel(this, sheet);
        soundPanel = new SoundPanel(this, sheet, bluetoothPanel);
        remotePanel = new RemotePanel(this, ui, sheet, () -> {
            main.removeCallbacks(remotePoll);
            remotePoll.run();
        });
        restyle();
        String open = getIntent().getStringExtra("open");
        if (open != null) main.post(() -> openShortcut(open));

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
        main.removeCallbacks(remotePoll);
        remotePoll.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(clockTick);
        main.removeCallbacks(remotePoll);
    }

    @Override
    protected void onDestroy() {
        sheet.close();
        unregisterReceiver(packageChanges);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        sheet.close();
        ViewGroup root = (ViewGroup) scroll.getParent();
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            if (root.getChildAt(i) instanceof KeystoneEditor) ((KeystoneEditor) root.getChildAt(i)).dismiss();
        }
        if (!openShortcut(intent.getStringExtra("open"))) {
            // HOME pressed while already home: back to the top.
            scroll.smoothScrollTo(0, 0);
            focusFirst(favoritesRow);
        }
    }

    /**
     * Screens other apps (and the remote) can open directly:
     * am start -n app.unidroid.launcher/.HomeActivity --es open
     * apps|remote|keystone|projection|wifi|bluetooth|settings|picture|sound|about
     */
    private boolean openShortcut(String open) {
        if ("apps".equals(open)) {
            focusFirst(appsRow);
        } else if ("remote".equals(open)) {
            remotePanel.open();
        } else if ("keystone".equals(open)) {
            projectionPanel.openKeystone();
        } else if ("projection".equals(open)) {
            projectionPanel.open();
        } else if ("wifi".equals(open)) {
            wifiPanel.open();
        } else if ("bluetooth".equals(open)) {
            bluetoothPanel.open();
        } else if ("settings".equals(open)) {
            settingsPanel.open();
        } else if ("picture".equals(open)) {
            picturePanel.open();
        } else if ("sound".equals(open)) {
            soundPanel.open();
        } else if ("about".equals(open)) {
            new AboutPanel(this, sheet).open();
        } else {
            return false;
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        // This is the home screen: Back only closes an open panel.
        sheet.onBack();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The remote's Menu key opens the launcher settings from anywhere on the home screen.
        if (keyCode == KeyEvent.KEYCODE_MENU && !sheet.isOpen()) {
            settingsPanel.open();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == WifiPanel.PERMISSION_REQUEST) wifiPanel.onPermissionResult();
    }

    // ------------------------------------------------------------------ SettingsPanel.Host

    @Override
    public void restyle() {
        int accent = prefs.accent();
        kicker.setTextColor(accent);
        String wallpaper = prefs.wallpaper();
        glow.setVisibility(Prefs.WALLPAPER_PLAIN.equals(wallpaper) ? View.GONE : View.VISIBLE);
        if (Prefs.WALLPAPER_ACCENT.equals(wallpaper)) animateTint(accent);
        timeBlock.setVisibility(prefs.showClock() ? View.VISIBLE : View.INVISIBLE);
        projectorSection.setVisibility(prefs.showProjector() ? View.VISIBLE : View.GONE);
        appsSection.setVisibility(prefs.showApps() ? View.VISIBLE : View.GONE);
        reload();
    }

    @Override
    public List<AppRepo.App> apps() {
        return apps;
    }

    @Override
    public void start(Intent intent, String label) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast(getString(R.string.launch_failed, label));
        }
    }

    // ------------------------------------------------------------------ layout

    private int px(float designUnits) {
        return ui.px(designUnits);
    }

    private FrameLayout buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.INK);

        // Wallpaper: a large soft glow, tinted with the focused app's colour or the accent.
        glow = new View(this);
        aurora = new GradientDrawable();
        aurora.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        aurora.setGradientRadius(px(700));
        aurora.setColors(new int[] {Ui.withAlpha(tint, 150), 0});
        glow.setBackground(aurora);
        FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(px(1500), px(1500), Gravity.TOP | Gravity.END);
        glow.setTranslationX(px(320));
        glow.setTranslationY(-px(640));
        root.addView(glow, glowLp);
        root.addView(scrim(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {Ui.withAlpha(Ui.INK, 235), Ui.withAlpha(Ui.INK, 90), 0}));
        root.addView(scrim(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {Ui.withAlpha(Ui.INK, 240), Ui.withAlpha(Ui.INK, 50), 0}));

        scroll = new ScrollView(this) {
            /**
             * Two resting positions instead of "just enough to show the focused row": the
             * top, or the hero text just below the safe edge. Text is never left half off screen,
             * where the projector's keystone would crop it.
             */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                int h = getHeight(), safe = px(SAFE_EDGE);
                int max = Math.max(0, getChildAt(0).getHeight() - h);
                int heroText = hero.getTop() + hero.getPaddingTop() - px(HERO_EDGE);
                for (int target : new int[] {0, Math.min(max, heroText)}) {
                    if (r.top - target >= safe && r.bottom - target <= h - safe) return target - getScrollY();
                }
                return super.computeScrollDeltaToGetChildRectOnScreen(r);
            }

            /** Down on the last row would scroll past the resting position: nothing there, stay. */
            @Override
            public boolean arrowScroll(int direction) {
                View focused = findFocus();
                if (FocusFinder.getInstance().findNextFocus(this, focused, direction) == null) return false;
                return super.arrowScroll(direction);
            }
        };
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

        View topBar = buildTopBar();
        column.addView(topBar);
        // The clock fades out as the rows scroll up, rather than sit half cut at the top edge.
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) ->
                topBar.setAlpha(Math.max(0f, 1f - y / (float) px(110))));
        hero = buildHero();
        column.addView(hero);
        favoritesRow = addRow(column, R.string.row_favorites);
        projectorRow = addRow(column, R.string.row_projector);
        projectorSection = (View) projectorRow.getParent().getParent();
        appsRow = addRow(column, R.string.row_apps);
        appsSection = (View) appsRow.getParent().getParent();
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
        bar.setClipToPadding(false); // the focused button grows into the right margin

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        clock = ui.text(92, Ui.FG, Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setLetterSpacing(-0.03f);
        date = ui.text(26, Ui.DIM, Typeface.DEFAULT);
        time.addView(clock);
        time.addView(date);
        timeBlock = time;
        bar.addView(time, new LinearLayout.LayoutParams(0, -2, 1));

        bar.addView(statusButton(R.drawable.ic_settings, R.string.settings_launcher, R.string.settings_launcher_sub, v -> settingsPanel.open()));
        return bar;
    }

    private View statusButton(int icon, int label, int sub, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        GradientDrawable bg = ui.glass(px(32));
        b.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(Ui.FG);
        b.addView(iv, new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(64), px(64));
        lp.leftMargin = px(16);
        lp.topMargin = px(10);
        b.setLayoutParams(lp);
        makeFocusable(b, 1.12f, getString(R.string.kicker_system), getString(label), getString(sub), 0, focused -> {
            bg.setColor(focused ? Ui.FG : Ui.GLASS);
            iv.setColorFilter(focused ? Ui.INK : Ui.FG);
        });
        b.setOnClickListener(click);
        return b;
    }

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(0, px(56), 0, 0);
        kicker = ui.text(22, prefs.accent(), Typeface.DEFAULT_BOLD);
        kicker.setLetterSpacing(0.14f);
        kicker.setAllCaps(true);
        title = ui.text(84, Ui.FG, Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.03f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = ui.text(28, Ui.DIM, Typeface.DEFAULT);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(kicker);
        hero.addView(title);
        hero.addView(subtitle);
        hero.setLayoutParams(new LinearLayout.LayoutParams(px(1500), px(240)));
        return hero;
    }

    /** Adds a heading and a horizontal row; both sit in one section so they hide together. */
    private LinearLayout addRow(LinearLayout column, int heading) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setClipChildren(false);
        section.setClipToPadding(false);
        TextView h = ui.text(26, Ui.DIM, Typeface.DEFAULT_BOLD);
        h.setText(heading);
        h.setPadding(px(24), px(18), 0, 0);
        section.addView(h);

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
        section.addView(hs);
        column.addView(section);
        return row;
    }

    // ------------------------------------------------------------------ content

    private void reload() {
        apps = AppRepo.load(this);
        AppExport.write(this, apps);
        List<AppRepo.App> favorites = AppRepo.favorites(prefs.raw(), apps);
        View focused = getCurrentFocus();

        favoritesRow.removeAllViews();
        for (AppRepo.App a : favorites) favoritesRow.addView(favoriteCard(a));
        favoritesRow.addView(addFavoriteCard());

        projectorRow.removeAllViews();
        projectorRow.addView(tile(R.drawable.ic_projector, getString(R.string.projection), null, getString(R.string.projection_sub),
                0xFF8F6BFF, v -> projectionPanel.open(), 220));
        projectorRow.addView(tile(R.drawable.ic_image, getString(R.string.picture), null, getString(R.string.picture_sub),
                0xFFFFB020, v -> picturePanel.open(), 220));
        projectorRow.addView(tile(R.drawable.ic_sound, getString(R.string.sound), null, getString(R.string.sound_sub),
                0xFF30D158, v -> soundPanel.open(), 220));
        if (ProjectionPanel.hasHdmi(this)) {
            projectorRow.addView(tile(R.drawable.ic_hdmi, getString(R.string.hdmi), null, getString(R.string.hdmi_sub),
                    0xFFFF9F43, v -> ProjectionPanel.openHdmi(this), 220));
        }
        projectorRow.addView(tile(R.drawable.ic_globe, getString(R.string.browser), null, getString(R.string.browser_sub),
                0xFF2ED3C6, v -> start(new Intent(this, BrowserActivity.class), getString(R.string.browser)), 220));
        projectorRow.addView(tile(R.drawable.ic_wifi, getString(R.string.wifi), null, getString(R.string.wifi_sub),
                0xFF4CC4FF, v -> wifiPanel.open(), 220));
        projectorRow.addView(tile(R.drawable.ic_bluetooth, getString(R.string.bluetooth), null, getString(R.string.bluetooth_sub),
                0xFF5B7CFF, v -> bluetoothPanel.open(), 220));
        projectorRow.addView(remoteTile());

        appsRow.removeAllViews();
        Set<String> hidden = prefs.hidden();
        for (AppRepo.App a : apps) {
            if (!hidden.contains(a.pkg())) appsRow.addView(appIcon(a));
        }

        if (!sheet.isOpen() && (focused == null || !focused.isAttachedToWindow())) main.post(() -> focusFirst(favoritesRow));
    }

    private View favoriteCard(AppRepo.App a) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {a.color, Ui.darken(a.color, 0.45f)});
        bg.setCornerRadius(px(20));
        card.setBackground(bg);
        card.setClipToOutline(true);
        int[] size = prefs.favoriteSize();
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
            int icon = size[1] * 4 / 9;
            inner.addView(iv, new LinearLayout.LayoutParams(px(icon), px(icon)));
            TextView label = ui.text(26, Color.WHITE, Typeface.DEFAULT_BOLD);
            label.setText(a.label);
            label.setSingleLine(true);
            label.setPadding(px(16), px(10), px(16), 0);
            inner.addView(label);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setLayoutParams(ui.card(size[0], size[1], 30));
        card.setAlpha(0.8f);
        makeFocusable(card, 1.1f, getString(R.string.kicker_favorite), a.label, a.category(this), a.color, focused -> {
            card.setAlpha(focused ? 1f : 0.8f);
            setRing(card, focused, 20);
        });
        card.setOnClickListener(v -> start(a.launchIntent(), a.label));
        card.setOnLongClickListener(v -> {
            settingsPanel.appMenu(a);
            return true;
        });
        return card;
    }

    private View addFavoriteCard() {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = ui.glass(px(20));
        card.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_add);
        iv.setColorFilter(Ui.DIM);
        card.addView(iv, new FrameLayout.LayoutParams(px(56), px(56), Gravity.CENTER));
        int h = prefs.favoriteSize()[1];
        card.setLayoutParams(ui.card(h, h, 30));
        makeFocusable(card, 1.1f, getString(R.string.kicker_customize), getString(R.string.add_favorite),
                getString(R.string.add_favorite_sub), 0xFF7A86A8, focused -> {
                    bg.setColor(focused ? Ui.FG : Ui.GLASS);
                    iv.setColorFilter(focused ? Ui.INK : Ui.DIM);
                });
        card.setOnClickListener(v -> {
            if (!prefs.showApps()) {
                settingsPanel.open();
                return;
            }
            focusFirst(appsRow);
            toast(getString(R.string.hold_to_favorite));
        });
        return card;
    }

    /**
     * Remote tile. Until a phone has added the remote to its home screen it is a wide
     * tile with a QR code to open it; then it becomes a tile like the others, with the
     * phone's status, opening the remote panel. Swapped in place when that changes.
     */
    private View remoteTile() {
        remoteTileQr = remoteShowsQr();
        remoteTileView = remoteTileQr ? remoteQrTile() : remoteButtonTile();
        renderRemoteTile();
        return remoteTileView;
    }

    private View remoteButtonTile() {
        View t = tile(R.drawable.ic_remote, getString(R.string.remote), "", getString(R.string.remote_tile_sub),
                0xFF0A84FF, v -> remotePanel.open(), 220);
        remoteLine1 = t.findViewWithTag("value");
        remoteLine1.setEllipsize(TextUtils.TruncateAt.END);
        remoteQr = null;
        remoteLine2 = null;
        return t;
    }

    private View remoteQrTile() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(14), px(14), px(24), px(14));
        GradientDrawable bg = ui.glass(px(22));
        t.setBackground(bg);

        remoteQr = new QrView(this);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(px(104), px(104));
        qrLp.rightMargin = px(20);
        t.addView(remoteQr, qrLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(24, Ui.FG, Typeface.DEFAULT_BOLD);
        name.setText(R.string.remote);
        texts.addView(name);
        remoteLine1 = ui.text(19, Ui.DIM, Typeface.DEFAULT);
        remoteLine1.setSingleLine(true);
        remoteLine1.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-1, -2);
        l1.topMargin = px(8);
        texts.addView(remoteLine1, l1);
        remoteLine2 = ui.text(19, Ui.DIM, Typeface.DEFAULT);
        remoteLine2.setSingleLine(true);
        remoteLine2.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, -2);
        l2.topMargin = px(4);
        texts.addView(remoteLine2, l2);
        t.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        final TextView line1 = remoteLine1, line2 = remoteLine2;
        t.setLayoutParams(ui.card(480, 132, 30));
        makeFocusable(t, 1.1f, getString(R.string.kicker_system), getString(R.string.remote), "", 0xFF0A84FF, focused -> {
            bg.setColor(focused ? Ui.FG : Ui.GLASS);
            name.setTextColor(focused ? Ui.INK : Ui.FG);
            line1.setTextColor(focused ? Ui.withAlpha(Ui.INK, 170) : Ui.DIM);
            line2.setTextColor(focused ? Ui.withAlpha(Ui.INK, 170) : Ui.DIM);
            if (focused) subtitle.setText(remoteSubtitle());
        });
        t.setOnClickListener(v -> remotePanel.open());
        return t;
    }

    private boolean remoteShowsQr() {
        return remoteState != null && remoteState.running && !remoteState.anyInstalled();
    }

    private void renderRemoteTile() {
        if (remoteTileView == null) return;
        if (remoteShowsQr() != remoteTileQr) {
            ViewGroup row = (ViewGroup) remoteTileView.getParent();
            if (row == null) return;
            boolean hadFocus = remoteTileView.hasFocus();
            int at = row.indexOfChild(remoteTileView);
            row.removeViewAt(at);
            View swapped = remoteTile();
            row.addView(swapped, at);
            if (hadFocus) swapped.requestFocus();
            return;
        }
        RemoteLink.State s = remoteState;
        if (remoteTileQr) {
            remoteQr.setText(s.url);
            remoteLine1.setText(R.string.remote_scan);
            remoteLine2.setText(s.url.substring(7));
        } else if (s == null) {
            remoteLine1.setText(R.string.loading);
        } else if (!s.running) {
            remoteLine1.setText(R.string.remote_unavailable);
        } else {
            // Installed on a phone: just "Remote", like the other tiles; the panel lists the phones.
            remoteLine1.setText("");
        }
        if (!remoteTileQr) remoteLine1.setVisibility(remoteLine1.length() == 0 ? View.GONE : View.VISIBLE);
        View focus = getCurrentFocus();
        if (focus != null && isInside(focus, remoteLine1)) {
            subtitle.setText(remoteSubtitle());
        }
    }

    private static boolean isInside(View parent, View child) {
        for (android.view.ViewParent p = child.getParent(); p != null; p = p.getParent()) {
            if (p == parent) return true;
        }
        return false;
    }

    private int remoteSubtitle() {
        if (remoteState != null && !remoteState.running) return R.string.remote_unavailable_sub;
        return remoteShowsQr() ? R.string.remote_scan_sub : R.string.remote_tile_sub;
    }

    private View tile(int icon, String label, String value, String sub, int color, View.OnClickListener click, int width) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(px(24), px(22), px(24), px(20));
        GradientDrawable bg = ui.glass(px(22));
        t.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(Ui.FG);
        t.addView(iv, new LinearLayout.LayoutParams(px(34), px(34)));
        View spacer = new View(this);
        t.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1));
        TextView name = ui.text(24, Ui.FG, Typeface.DEFAULT_BOLD);
        name.setText(label);
        t.addView(name);
        TextView val = null;
        if (value != null) {
            val = ui.text(19, Ui.DIM, Typeface.DEFAULT);
            val.setText(value);
            val.setTag("value");
            val.setSingleLine(true);
            t.addView(val);
        }
        final TextView valueView = val;
        t.setLayoutParams(ui.card(width, 132, 30));
        makeFocusable(t, 1.1f, getString(R.string.kicker_system), label, sub, color, focused -> {
            bg.setColor(focused ? Ui.FG : Ui.GLASS);
            iv.setColorFilter(focused ? Ui.INK : Ui.FG);
            name.setTextColor(focused ? Ui.INK : Ui.FG);
            if (valueView != null) valueView.setTextColor(focused ? Ui.withAlpha(Ui.INK, 170) : Ui.DIM);
        });
        t.setOnClickListener(click);
        return t;
    }

    private View appIcon(AppRepo.App a) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = ui.glass(px(34));
        box.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        box.addView(iv, new FrameLayout.LayoutParams(px(84), px(84), Gravity.CENTER));
        col.addView(box, new LinearLayout.LayoutParams(px(132), px(132)));
        TextView label = ui.text(21, Ui.DIM, Typeface.DEFAULT);
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(px(170), -2);
        llp.topMargin = px(14);
        col.addView(label, llp);
        col.setLayoutParams(ui.card(170, -1, 14));
        makeFocusable(col, 1.1f, getString(R.string.kicker_app), a.label, a.category(this), a.color, focused -> {
            bg.setColor(focused ? Ui.FG : Ui.GLASS);
            label.setTextColor(focused ? Ui.FG : Ui.DIM);
        });
        col.setOnClickListener(v -> start(a.launchIntent(), a.label));
        col.setOnLongClickListener(v -> {
            settingsPanel.appMenu(a);
            return true;
        });
        return col;
    }

    // ------------------------------------------------------------------ behaviour

    private interface FocusStyle {
        void apply(boolean focused);
    }

    /** color 0 = use the accent colour. */
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
                setTint(color != 0 ? color : prefs.accent());
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
        ring.setStroke(px(3), Ui.withAlpha(Color.WHITE, 235));
        v.setForeground(ring);
    }

    /** Follows the focused item, unless the wallpaper is set to a fixed colour. */
    private void setTint(int target) {
        if (!Prefs.WALLPAPER_DYNAMIC.equals(prefs.wallpaper())) return;
        animateTint(target);
    }

    private void animateTint(int target) {
        if (tintAnimator != null) tintAnimator.cancel();
        tintAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), tint, target);
        tintAnimator.setDuration(900);
        tintAnimator.addUpdateListener(an -> {
            tint = (int) an.getAnimatedValue();
            aurora.setColors(new int[] {Ui.withAlpha(tint, 150), 0});
        });
        tintAnimator.start();
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
