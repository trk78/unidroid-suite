package app.unidroid.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Native projector panel. Keystone is applied the way the vendor settings app does it
 * on Allwinner H713 projectors: eight corner offsets kept in persist.display.keystone_*
 * and pushed live to SurfaceFlinger (transaction 1050). Needs root.
 */
final class ProjectionPanel {
    private static final int COLOR = 0xFF8F6BFF;
    private static final int SF_KEYSTONE = 1050;
    private static final int STEP = 2;
    /** Corner keys, in the order SurfaceFlinger expects them. */
    private static final String[] KEYS = {"lbx", "lby", "ltx", "lty", "rtx", "rty", "rbx", "rby"};
    /** Rows shown to the user: top-left, top-right, bottom-left, bottom-right (index of x in KEYS). */
    private static final int[] CORNERS = {2, 4, 0, 6};
    private static final int[] CORNER_NAMES = {R.string.corner_tl, R.string.corner_tr, R.string.corner_bl, R.string.corner_br};

    private static final ComponentName VENDOR_SETTINGS = new ComponentName("com.htc.htcsettings", "com.htc.activity.ProjectionActivity");
    private static final ComponentName HDMI_SOURCE = new ComponentName("com.softwinner.awsource", "com.softwinner.awsource.MainActivity");

    private final Activity act;
    private final Sheet sheet;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int[] values;
    private int[] opened;
    private final Runnable push = this::apply;

    ProjectionPanel(Activity act, Sheet sheet) {
        this.act = act;
        this.sheet = sheet;
    }

    static boolean hasHdmi(Activity act) {
        return new Intent().setComponent(HDMI_SOURCE).resolveActivity(act.getPackageManager()) != null;
    }

    static void openHdmi(Activity act) {
        try {
            act.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(HDMI_SOURCE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(act, act.getString(R.string.launch_failed, "HDMI"), Toast.LENGTH_SHORT).show();
        }
    }

    void open() {
        sheet.open(R.drawable.ic_projector, act.getString(R.string.projection), act.getString(R.string.projection_sub), COLOR, () -> main.removeCallbacks(push));
        sheet.note(act.getString(R.string.loading));
        Root.check((ok, out) -> {
            if (!sheet.isOpen()) return;
            if (!ok) {
                noRoot();
                return;
            }
            StringBuilder cmd = new StringBuilder();
            for (String k : KEYS) cmd.append("getprop persist.display.keystone_").append(k).append(';');
            Root.run(cmd.toString(), (ok2, props) -> {
                String[] lines = props.split("\\s+");
                if (lines.length < KEYS.length) {
                    noRoot();
                    return;
                }
                values = new int[KEYS.length];
                for (int i = 0; i < KEYS.length; i++) values[i] = parse(lines[i]);
                opened = values.clone();
                render();
                sheet.focus();
            });
        });
    }

    private void noRoot() {
        sheet.clear();
        sheet.note(act.getString(R.string.projection_no_root));
        vendorRows();
        sheet.focus();
    }

    private void render() {
        sheet.clear();
        sheet.section(act.getString(R.string.keystone));
        for (int c = 0; c < CORNERS.length; c++) {
            final int x = CORNERS[c];
            final int name = CORNER_NAMES[c];
            final Sheet.Row[] row = new Sheet.Row[1];
            row[0] = sheet.item(0, act.getString(name), coords(x), v -> edit(row[0], x));
        }
        sheet.item(0, act.getString(R.string.keystone_undo), act.getString(R.string.keystone_undo_sub), v -> {
            values = opened.clone();
            apply();
            render();
            sheet.focusAt(4);
        });
        if (hasHdmi(act)) {
            sheet.section(act.getString(R.string.source));
            sheet.item(R.drawable.ic_hdmi, act.getString(R.string.hdmi), act.getString(R.string.hdmi_sub), v -> openHdmi(act));
        }
        vendorRows();
    }

    private void vendorRows() {
        Intent vendor = new Intent().setComponent(VENDOR_SETTINGS);
        if (vendor.resolveActivity(act.getPackageManager()) == null) return;
        sheet.section(act.getString(R.string.vendor_section));
        sheet.item(R.drawable.ic_settings, act.getString(R.string.vendor_projection), act.getString(R.string.vendor_projection_sub), v -> {
            try {
                act.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(VENDOR_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                // The vendor screen is not exported on every firmware: open it as root.
                Root.run("am start -n " + VENDOR_SETTINGS.flattenToString(), null);
            }
        });
    }

    /** Arrows move the corner live; OK keeps it, Back puts it back. */
    private void edit(Sheet.Row row, int x) {
        final int startX = values[x], startY = values[x + 1];
        sheet.subtitle(act.getString(R.string.keystone_editing));
        row.trailing("✥");
        row.view.setOnKeyListener((v, keyCode, e) -> {
            boolean back = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE;
            boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
            if (back || ok) {
                // Finish on key up, so the release does not click the row again.
                if (e.getAction() != KeyEvent.ACTION_UP) return true;
                if (back) {
                    values[x] = startX;
                    values[x + 1] = startY;
                    apply();
                }
                row.view.setOnKeyListener(null);
                row.trailing(null);
                row.value(coords(x));
                sheet.subtitle(act.getString(R.string.projection_sub));
                return true;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: values[x] -= STEP; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: values[x] += STEP; break;
                case KeyEvent.KEYCODE_DPAD_UP: values[x + 1] += STEP; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: values[x + 1] -= STEP; break;
                default: return false;
            }
            row.value(coords(x));
            main.removeCallbacks(push);
            main.postDelayed(push, 120);
            return true;
        });
    }

    private void apply() {
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            cmd.append("setprop persist.display.keystone_").append(KEYS[i]).append(' ').append(values[i]).append(';');
        }
        cmd.append("service call SurfaceFlinger ").append(SF_KEYSTONE);
        for (int v : values) cmd.append(" f ").append(v);
        Root.run(cmd.toString(), (ok, out) -> {
            if (!ok) Toast.makeText(act, act.getString(R.string.action_failed), Toast.LENGTH_SHORT).show();
        });
    }

    private String coords(int x) {
        return "x " + values[x] + " · y " + values[x + 1];
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
