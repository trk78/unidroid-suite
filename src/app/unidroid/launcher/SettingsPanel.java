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

/**
 * Settings, by category: projector (projection, picture, sound), network (Wi-Fi,
 * Bluetooth), home screen (look, rows, hidden apps) and system (sleep timer, Android
 * settings, about). Also the per-app menu opened by holding OK.
 */
final class SettingsPanel {
    interface Host {
        /** Re-applies colours, sizes and visible rows. */
        void restyle();

        List<AppRepo.App> apps();

        void start(Intent intent, String label);

        /** Opens another panel: projection, picture, sound, wifi, bluetooth. */
        void openPanel(String name);
    }

    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90, 120};

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
        sheet.section(act.getString(R.string.cat_projector));
        sheet.item(R.drawable.ic_projector, act.getString(R.string.projection), act.getString(R.string.projection_sub), v -> host.openPanel("projection"));
        sheet.item(R.drawable.ic_image, act.getString(R.string.picture), act.getString(R.string.picture_sub), v -> host.openPanel("picture"));
        sheet.item(R.drawable.ic_sound, act.getString(R.string.sound), act.getString(R.string.sound_sub), v -> host.openPanel("sound"));

        sheet.section(act.getString(R.string.cat_network));
        sheet.item(R.drawable.ic_wifi, act.getString(R.string.wifi), WifiPanel.summary(act), v -> host.openPanel("wifi"));
        sheet.item(R.drawable.ic_bluetooth, act.getString(R.string.bluetooth), BluetoothPanel.summary(act), v -> host.openPanel("bluetooth"));

        sheet.section(act.getString(R.string.cat_home));
        sheet.item(R.drawable.ic_settings, act.getString(R.string.look), act.getString(R.string.look_sub), v -> {
            appearance();
            sheet.focus();
        });

        sheet.section(act.getString(R.string.system));
        String[] labels = new String[SLEEP_MINUTES.length];
        labels[0] = act.getString(R.string.off);
        for (int i = 1; i < labels.length; i++) labels[i] = act.getString(R.string.minutes, SLEEP_MINUTES[i]);
        Sheet.Row timer = sheet.choice(0, act.getString(R.string.sleep_timer), labels, 0,
                i -> RemoteLink.call("/action?a=sleep:" + SLEEP_MINUTES[i]));
        RemoteLink.refresh(act, false, s -> {
            if (s.sleepAt > 0) timer.value(act.getString(R.string.sleep_at,
                    android.text.format.DateFormat.getTimeFormat(act).format(new java.util.Date(s.sleepAt))));
        });
        sheet.item(R.drawable.ic_settings, act.getString(R.string.settings), act.getString(R.string.settings_sub),
                v -> host.start(new Intent(Settings.ACTION_SETTINGS), act.getString(R.string.settings)));
        sheet.item(0, act.getString(R.string.about), act.getString(R.string.about_sub, version(act)), v -> new AboutPanel(act, sheet).open());
    }

    /** Home screen look, rows and hidden apps. */
    private void appearance() {
        sheet.clear();
        sheet.item(0, "\u2039 " + act.getString(R.string.back), null, v -> {
            render();
            sheet.focus();
        });
        sheet.section(act.getString(R.string.background));
        sheet.choice(0, act.getString(R.string.background_style), strings(R.string.bg_glow, R.string.bg_aurora, R.string.bg_nebula,
                R.string.bg_gradient, R.string.bg_halo, R.string.bg_plain),
                Prefs.indexOf(Prefs.WALLPAPERS, prefs.wallpaper()), i -> {
                    prefs.setWallpaper(Prefs.WALLPAPERS[i]);
                    host.restyle();
                }).value(act.getString(R.string.background_style_sub));
        sheet.choice(0, act.getString(R.string.background_colors), strings(R.string.pal_accent, R.string.pal_ocean, R.string.pal_dusk,
                R.string.pal_ember, R.string.pal_forest, R.string.pal_lavender),
                Prefs.indexOf(Backdrop.PALETTES, prefs.palette()), i -> {
                    prefs.setPalette(Backdrop.PALETTES[i]);
                    host.restyle();
                });
        String[] accents = strings(R.string.accent_blue, R.string.accent_violet, R.string.accent_pink,
                R.string.accent_orange, R.string.accent_green, R.string.accent_teal);
        sheet.choice(0, act.getString(R.string.accent), accents, indexOf(Prefs.ACCENTS, prefs.accent()), i -> {
            prefs.setAccent(Prefs.ACCENTS[i]);
            host.restyle();
        });

        sheet.section(act.getString(R.string.clock_section));
        sheet.choice(0, act.getString(R.string.clock_style), strings(R.string.clock_thin, R.string.clock_bold, R.string.clock_compact, R.string.clock_hidden),
                Prefs.indexOf(Prefs.CLOCKS, prefs.clockStyle()), i -> {
                    prefs.setClockStyle(Prefs.CLOCKS[i]);
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
        sheet.item(0, act.getString(R.string.row_order), act.getString(R.string.row_order_sub), v -> rowOrder());

        sheet.section(act.getString(R.string.row_favorites));
        sheet.choice(0, act.getString(R.string.card_size), strings(R.string.size_compact, R.string.size_normal, R.string.size_large),
                Prefs.indexOf(Prefs.SIZES, prefs.cardSize()), i -> {
                    prefs.setCardSize(Prefs.SIZES[i]);
                    host.restyle();
                });

        sheet.section(act.getString(R.string.row_apps));
        sheet.choice(0, act.getString(R.string.app_sort), strings(R.string.sort_alpha, R.string.sort_custom),
                Prefs.indexOf(Prefs.SORTS, prefs.appSort()), i -> {
                    prefs.setAppSort(Prefs.SORTS[i]);
                    host.restyle();
                }).value(act.getString(R.string.app_sort_sub));
        sheet.choice(0, act.getString(R.string.icon_size), strings(R.string.size_compact, R.string.size_normal, R.string.size_large),
                Prefs.indexOf(Prefs.SIZES, prefs.iconSize()), i -> {
                    prefs.setIconSize(Prefs.SIZES[i]);
                    host.restyle();
                });
        sheet.toggle(0, act.getString(R.string.show_names), null, prefs.showNames(), on -> {
            prefs.setShowNames(on);
            host.restyle();
        });
        Set<String> hidden = prefs.hidden();
        sheet.item(0, act.getString(R.string.hidden_apps), act.getResources().getQuantityString(R.plurals.hidden_count, hidden.size(), hidden.size()),
                v -> hiddenApps());

    }

    /** Up / down for each home row. */
    private void rowOrder() {
        sheet.clear();
        sheet.item(0, "\u2039 " + act.getString(R.string.back), null, v -> {
            appearance();
            sheet.focus();
        });
        sheet.section(act.getString(R.string.row_order));
        int[] names = {R.string.row_favorites, R.string.row_projector, R.string.row_apps};
        java.util.List<String> order = prefs.rowOrder();
        for (int i = 0; i < order.size(); i++) {
            String row = order.get(i);
            String label = act.getString(names[java.util.Arrays.asList(Prefs.ROWS).indexOf(row)]);
            final int at = i;
            sheet.item(0, label, act.getString(R.string.row_move_sub), v -> {
                prefs.moveRow(row, at == 0 ? 1 : -1); // OK moves it up (the first one goes down)
                host.restyle();
                rowOrder();
                sheet.focusAt(Math.max(1, at == 0 ? 2 : at));
            }).trailing((i + 1) + "");
        }
        sheet.focus();
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
            appearance();
            sheet.focus();
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
        // Move inside All apps (switches the row to the custom order).
        List<AppRepo.App> shown = prefs.sortApps(apps);
        shown.removeIf(x -> prefs.hidden().contains(x.pkg()));
        sheet.item(0, act.getString(R.string.menu_apps_left), null, v -> {
            prefs.moveApp(shown, a, -1);
            host.restyle();
            appMenu(a);
        });
        sheet.item(0, act.getString(R.string.menu_apps_right), null, v -> {
            prefs.moveApp(shown, a, 1);
            host.restyle();
            appMenu(a);
        });
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

    static String version(android.content.Context act) {
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
