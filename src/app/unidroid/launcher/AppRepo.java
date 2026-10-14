package app.unidroid.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed apps and the user's favorites. */
final class AppRepo {
    private static final String PREF_FAVORITES = "favorites";
    /** First-run favorites, in order, when installed. */
    private static final List<String> DEFAULT_FAVORITES = Arrays.asList(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.crunchyroll.crunchyroid", "com.plexapp.android",
            "com.spotify.tv.android", "com.phlox.tvwebbrowser");
    private static final int DEFAULT_FAVORITE_COUNT = 6;

    static final class App {
        final ComponentName component;
        final String category;
        final String label;
        final Drawable icon;
        final Drawable banner;
        final int color;

        App(ComponentName component, String category, String label, Drawable icon, Drawable banner) {
            this.component = component;
            this.category = category;
            this.label = label;
            this.icon = icon;
            this.banner = banner;
            this.color = dominantColor(banner != null ? banner : icon);
        }

        String pkg() {
            return component.getPackageName();
        }

        /** Localised Play category ("Video", "Games"...), empty when the app declares none. */
        String category(Context ctx) {
            if (Build.VERSION.SDK_INT < 26) return "";
            try {
                int cat = ctx.getPackageManager().getApplicationInfo(pkg(), 0).category;
                CharSequence title = ApplicationInfo.getCategoryTitle(ctx, cat);
                return title != null ? title.toString() : "";
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    /** Every launchable app, TV entry point preferred, sorted by name. */
    static List<App> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> byPackage = new LinkedHashMap<>();
        collect(pm, byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(pm, byPackage, Intent.CATEGORY_LAUNCHER);
        byPackage.remove(ctx.getPackageName());
        List<App> apps = new ArrayList<>(byPackage.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    private static void collect(PackageManager pm, Map<String, App> out, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (out.containsKey(ai.packageName)) continue;
            out.put(ai.packageName, new App(new ComponentName(ai.packageName, ai.name), category,
                    ri.loadLabel(pm).toString(), ai.loadIcon(pm), ai.loadBanner(pm)));
        }
    }

    static List<App> favorites(SharedPreferences prefs, List<App> apps) {
        Map<String, App> byPackage = new LinkedHashMap<>();
        for (App a : apps) byPackage.put(a.pkg(), a);
        String saved = prefs.getString(PREF_FAVORITES, null);
        List<App> result = new ArrayList<>();
        if (saved == null) {
            for (String pkg : DEFAULT_FAVORITES) {
                if (byPackage.containsKey(pkg) && result.size() < DEFAULT_FAVORITE_COUNT) result.add(byPackage.get(pkg));
            }
            for (App a : apps) {
                if (result.size() >= DEFAULT_FAVORITE_COUNT) break;
                if (a.banner != null && !result.contains(a)) result.add(a);
            }
            save(prefs, result);
            return result;
        }
        for (String pkg : saved.split(",")) {
            if (byPackage.containsKey(pkg)) result.add(byPackage.get(pkg));
        }
        return result;
    }

    /** Adds or removes an app; returns true when it is now a favorite. */
    static boolean toggleFavorite(SharedPreferences prefs, List<App> apps, App app) {
        List<App> favs = favorites(prefs, apps);
        boolean added = !favs.remove(app);
        if (added) favs.add(app);
        save(prefs, favs);
        return added;
    }

    /** Moves a favorite one place left (-1) or right (+1). Returns false at either end. */
    static boolean moveFavorite(SharedPreferences prefs, List<App> apps, App app, int delta) {
        List<App> favs = favorites(prefs, apps);
        int from = favs.indexOf(app);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= favs.size()) return false;
        Collections.swap(favs, from, to);
        save(prefs, favs);
        return true;
    }

    static boolean isFavorite(SharedPreferences prefs, List<App> apps, App app) {
        return favorites(prefs, apps).contains(app);
    }

    private static void save(SharedPreferences prefs, List<App> favs) {
        StringBuilder sb = new StringBuilder();
        for (App a : favs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a.pkg());
        }
        prefs.edit().putString(PREF_FAVORITES, sb.toString()).apply();
    }

    /** Saturation-weighted average colour, kept bright enough to tint a dark background. */
    static int dominantColor(Drawable d) {
        if (d == null) return 0xFF3D6FFF;
        Bitmap bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, 24, 24);
        copy.draw(new Canvas(bmp));
        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, total = 0;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int c = bmp.getPixel(x, y);
                if (Color.alpha(c) < 200) continue;
                Color.colorToHSV(c, hsv);
                double w = hsv[1] * hsv[2] + 0.02;
                r += Color.red(c) * w;
                g += Color.green(c) * w;
                b += Color.blue(c) * w;
                total += w;
            }
        }
        bmp.recycle();
        if (total < 1) return 0xFF3D6FFF;
        Color.RGBToHSV((int) (r / total), (int) (g / total), (int) (b / total), hsv);
        hsv[1] = Math.max(hsv[1], 0.35f);
        hsv[2] = Math.min(Math.max(hsv[2], 0.55f), 0.95f);
        return Color.HSVToColor(hsv);
    }
}
