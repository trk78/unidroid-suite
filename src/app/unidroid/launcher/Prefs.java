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
    static final String[] WALLPAPERS = {WALLPAPER_DYNAMIC, WALLPAPER_ACCENT, WALLPAPER_PLAIN};

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
}
