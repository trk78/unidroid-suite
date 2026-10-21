package app.unidroid.launcher;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Look-and-feel choices, stored next to the favorites. */
final class Prefs {
    static final String WALLPAPER_DYNAMIC = "dynamic";
    static final String WALLPAPER_ACCENT = "accent";
    static final String WALLPAPER_PLAIN = "plain";
    static final String[] WALLPAPERS = Backdrop.STYLES;

    static final String[] CLOCKS = {"thin", "bold", "compact", "hidden"};
    static final String[] ROWS = {"favorites", "projector", "apps"};
    static final String[] SORTS = {"alpha", "custom"};

    static final int[] ACCENTS = {0xFF6AA8FF, 0xFF8F6BFF, 0xFFFF6B9A, 0xFFFF9F43, 0xFF3DDC84, 0xFF2ED3C6};

    static final String SIZE_COMPACT = "compact";
    static final String SIZE_NORMAL = "normal";
    static final String SIZE_LARGE = "large";
    static final String[] SIZES = {SIZE_COMPACT, SIZE_NORMAL, SIZE_LARGE};

    private final SharedPreferences sp;

    Prefs(SharedPreferences sp) {
        this.sp = sp;
    }

    SharedPreferences raw() {
        return sp;
    }

    String wallpaper() {
        return sp.getString("wallpaper", WALLPAPER_DYNAMIC);
    }

    void setWallpaper(String v) {
        sp.edit().putString("wallpaper", v).apply();
    }

    int accent() {
        return sp.getInt("accent", ACCENTS[0]);
    }

    void setAccent(int v) {
        sp.edit().putInt("accent", v).apply();
    }

    boolean showClock() {
        return sp.getBoolean("show_clock", true);
    }

    void setShowClock(boolean v) {
        sp.edit().putBoolean("show_clock", v).apply();
    }

    boolean showProjector() {
        return sp.getBoolean("show_projector", true);
    }

    void setShowProjector(boolean v) {
        sp.edit().putBoolean("show_projector", v).apply();
    }

    boolean showApps() {
        return sp.getBoolean("show_apps", true);
    }

    void setShowApps(boolean v) {
        sp.edit().putBoolean("show_apps", v).apply();
    }

    String cardSize() {
        return sp.getString("card_size", SIZE_NORMAL);
    }

    void setCardSize(String v) {
        sp.edit().putString("card_size", v).apply();
    }

    /** Favorite banner size in design units, 16:9. */
    int[] favoriteSize() {
        switch (cardSize()) {
            case SIZE_COMPACT: return new int[] {288, 162};
            case SIZE_LARGE: return new int[] {432, 243};
            default: return new int[] {352, 198};
        }
    }

    Set<String> hidden() {
        String s = sp.getString("hidden", "");
        Set<String> out = new LinkedHashSet<>();
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        return out;
    }

    void setHidden(String pkg, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(pkg);
        else h.remove(pkg);
        sp.edit().putString("hidden", String.join(",", new ArrayList<>(h))).apply();
    }

    static int indexOf(String[] values, String v) {
        List<String> l = Arrays.asList(values);
        return Math.max(0, l.indexOf(v));
    }

    /** Picture zoom in percent (100 = none), applied on top of the keystone. */
    int zoom() {
        return sp.getInt("zoom", 100);
    }

    void setZoom(int percent) {
        sp.edit().putInt("zoom", percent).apply();
    }

    String palette() {
        return sp.getString("palette", "accent");
    }

    void setPalette(String v) {
        sp.edit().putString("palette", v).apply();
    }

    /** thin, bold, compact or hidden (the old on/off switch maps to thin / hidden). */
    String clockStyle() {
        return sp.getString("clock", sp.getBoolean("show_clock", true) ? "thin" : "hidden");
    }

    void setClockStyle(String v) {
        sp.edit().putString("clock", v).apply();
    }

    /** Order of the home rows, as ROWS names. */
    List<String> rowOrder() {
        List<String> out = new ArrayList<>();
        for (String r : sp.getString("row_order", String.join(",", ROWS)).split(",")) {
            if (Arrays.asList(ROWS).contains(r) && !out.contains(r)) out.add(r);
        }
        for (String r : ROWS) if (!out.contains(r)) out.add(r);
        return out;
    }

    void moveRow(String row, int delta) {
        List<String> order = rowOrder();
        int from = order.indexOf(row), to = from + delta;
        if (from < 0 || to < 0 || to >= order.size()) return;
        java.util.Collections.swap(order, from, to);
        sp.edit().putString("row_order", String.join(",", order)).apply();
    }

    /** alpha, or custom (the order set with Move in the app menu). */
    String appSort() {
        return sp.getString("app_sort", "alpha");
    }

    void setAppSort(String v) {
        sp.edit().putString("app_sort", v).apply();
    }

    /** Puts apps in the saved custom order; apps not in it yet keep their name order at the end. */
    List<AppRepo.App> sortApps(List<AppRepo.App> apps) {
        if (!"custom".equals(appSort())) return apps;
        List<String> order = Arrays.asList(sp.getString("app_order", "").split(","));
        List<AppRepo.App> out = new ArrayList<>(apps);
        java.util.Collections.sort(out, (a, b) -> {
            int ia = order.indexOf(a.pkg()), ib = order.indexOf(b.pkg());
            if (ia < 0 && ib < 0) return 0;
            if (ia < 0) return 1;
            if (ib < 0) return -1;
            return Integer.compare(ia, ib);
        });
        return out;
    }

    /** Moves an app one place in the custom order of All apps. */
    void moveApp(List<AppRepo.App> shown, AppRepo.App app, int delta) {
        List<String> order = new ArrayList<>();
        for (AppRepo.App a : shown) order.add(a.pkg());
        int from = order.indexOf(app.pkg()), to = from + delta;
        if (from < 0 || to < 0 || to >= order.size()) return;
        java.util.Collections.swap(order, from, to);
        sp.edit().putString("app_order", String.join(",", order)).putString("app_sort", "custom").apply();
    }

    boolean showNames() {
        return sp.getBoolean("show_names", true);
    }

    void setShowNames(boolean v) {
        sp.edit().putBoolean("show_names", v).apply();
    }

    String iconSize() {
        return sp.getString("icon_size", SIZE_NORMAL);
    }

    void setIconSize(String v) {
        sp.edit().putString("icon_size", v).apply();
    }

    /** All-apps tile, icon and column width, in design units. */
    int[] iconBox() {
        switch (iconSize()) {
            case SIZE_COMPACT: return new int[] {104, 66, 140};
            case SIZE_LARGE: return new int[] {160, 102, 200};
            default: return new int[] {132, 84, 170};
        }
    }
}
