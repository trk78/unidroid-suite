package app.unidroid.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

/** Launcher settings (look, rows, hidden apps) and the per-app menu opened by holding OK. */
final class SettingsPanel {
    interface Host {
        /** Re-applies colours, sizes and visible rows. */
        void restyle();

        List<AppRepo.App> apps();

        void start(Intent intent, String label);
    }

    private final Activity act;
    private final Sheet sheet;
    private final Prefs prefs;
    private final Host host;

    SettingsPanel(Activity act, Sheet sheet, Prefs prefs, Host host) {
        this.act = act;
        this.sheet = sheet;
        this.prefs = prefs;
        this.host = host;
    }

    // ------------------------------------------------------------------ settings

    void open() {
        sheet.open(R.drawable.ic_settings, act.getString(R.string.settings_launcher), act.getString(R.string.settings_launcher_sub), prefs.accent(), null);
        render();
        sheet.focus();
    }

    private void render() {
        sheet.clear();
        sheet.section(act.getString(R.string.look));
        sheet.choice(0, act.getString(R.string.wallpaper), strings(R.string.wallpaper_dynamic, R.string.wallpaper_accent, R.string.wallpaper_plain),
                Prefs.indexOf(Prefs.WALLPAPERS, prefs.wallpaper()), i -> {
                    prefs.setWallpaper(Prefs.WALLPAPERS[i]);
                    host.restyle();
                });
        String[] accents = strings(R.string.accent_blue, R.string.accent_violet, R.string.accent_pink,
                R.string.accent_orange, R.string.accent_green, R.string.accent_teal);
        sheet.choice(0, act.getString(R.string.accent), accents, indexOf(Prefs.ACCENTS, prefs.accent()), i -> {
            prefs.setAccent(Prefs.ACCENTS[i]);
            host.restyle();
        });
        sheet.choice(0, act.getString(R.string.card_size), strings(R.string.size_compact, R.string.size_normal, R.string.size_large),
                Prefs.indexOf(Prefs.SIZES, prefs.cardSize()), i -> {
                    prefs.setCardSize(Prefs.SIZES[i]);
                    host.restyle();
                });
        sheet.toggle(0, act.getString(R.string.show_clock), null, prefs.showClock(), on -> {
            prefs.setShowClock(on);
            host.restyle();
        });

        sheet.section(act.getString(R.string.home_rows));
        sheet.toggle(0, act.getString(R.string.row_projector), null, prefs.showProjector(), on -> {
            prefs.setShowProjector(on);
            host.restyle();
        });
        sheet.toggle(0, act.getString(R.string.row_apps), null, prefs.showApps(), on -> {
            prefs.setShowApps(on);
            host.restyle();
        });
        Set<String> hidden = prefs.hidden();
        sheet.item(0, act.getString(R.string.hidden_apps), act.getResources().getQuantityString(R.plurals.hidden_count, hidden.size(), hidden.size()),
                v -> hiddenApps());

        sheet.section(act.getString(R.string.system));
        sheet.item(R.drawable.ic_settings, act.getString(R.string.settings), act.getString(R.string.settings_sub),
                v -> host.start(new Intent(Settings.ACTION_SETTINGS), act.getString(R.string.settings)));
        sheet.item(0, act.getString(R.string.about), act.getString(R.string.about_sub, version()), null);
    }

    private void hiddenApps() {
        sheet.clear();
        sheet.section(act.getString(R.string.hidden_apps));
        Set<String> hidden = prefs.hidden();
        if (hidden.isEmpty()) sheet.note(act.getString(R.string.hidden_none));
        for (AppRepo.App a : host.apps()) {
            if (!hidden.contains(a.pkg())) continue;
            sheet.item(0, a.label, act.getString(R.string.unhide), v -> {
                prefs.setHidden(a.pkg(), false);
                host.restyle();
                hiddenApps();
            });
        }
        sheet.item(0, act.getString(R.string.back), null, v -> {
            render();
            sheet.focusAt(6);
        });
        sheet.focus();
    }

    // ------------------------------------------------------------------ app menu

    void appMenu(AppRepo.App a) {
        sheet.open(a.icon, a.label, a.category(act), a.color, null);
        List<AppRepo.App> apps = host.apps();
        boolean fav = AppRepo.isFavorite(prefs.raw(), apps, a);

        sheet.item(0, act.getString(R.string.menu_open), null, v -> {
            sheet.close();
            host.start(a.launchIntent(), a.label);
        });
        sheet.item(0, act.getString(fav ? R.string.menu_unfavorite : R.string.menu_favorite), null, v -> {
            boolean added = AppRepo.toggleFavorite(prefs.raw(), apps, a);
            toast(act.getString(added ? R.string.fav_added : R.string.fav_removed, a.label));
            host.restyle();
            appMenu(a);
        });
        if (fav) {
            sheet.item(0, act.getString(R.string.menu_move_left), null, v -> {
                if (AppRepo.moveFavorite(prefs.raw(), apps, a, -1)) host.restyle();
            });
            sheet.item(0, act.getString(R.string.menu_move_right), null, v -> {
                if (AppRepo.moveFavorite(prefs.raw(), apps, a, 1)) host.restyle();
            });
        }
        boolean hidden = prefs.hidden().contains(a.pkg());
        sheet.item(0, act.getString(hidden ? R.string.unhide : R.string.menu_hide), act.getString(R.string.menu_hide_sub), v -> {
            prefs.setHidden(a.pkg(), !hidden);
            host.restyle();
            sheet.close();
        });
        sheet.item(0, act.getString(R.string.menu_info), null, v -> {
            sheet.close();
            host.start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg())), a.label);
        });
        if (!isSystem(a.pkg())) {
            sheet.item(0, act.getString(R.string.menu_uninstall), null, v -> {
                sheet.close();
                host.start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg())), a.label);
            });
        }
        sheet.focus();
    }

    // ------------------------------------------------------------------ helpers

    private boolean isSystem(String pkg) {
        try {
            return (act.getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private String version() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private String[] strings(int... ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = act.getString(ids[i]);
        return out;
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
