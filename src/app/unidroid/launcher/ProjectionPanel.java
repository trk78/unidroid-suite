package app.unidroid.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Native projector panel: keystone (full-screen {@link KeystoneEditor}, needs root),
 * HDMI input, and the vendor screen for what is not mapped yet (projection mode, zoom).
 */
final class ProjectionPanel {
    private static final int COLOR = 0xFF8F6BFF;

    private static final ComponentName VENDOR_SETTINGS = new ComponentName("com.htc.htcsettings", "com.htc.activity.ProjectionActivity");
    private static final ComponentName HDMI_SOURCE = new ComponentName("com.softwinner.awsource", "com.softwinner.awsource.MainActivity");

    private final Activity act;
    private final Ui ui;
    private final Sheet sheet;
    private final FrameLayout host;
    private final ViewGroup home;
    private final Prefs prefs;

    ProjectionPanel(Activity act, Ui ui, Sheet sheet, FrameLayout host, ViewGroup home, Prefs prefs) {
        this.act = act;
        this.ui = ui;
        this.sheet = sheet;
        this.host = host;
        this.home = home;
        this.prefs = prefs;
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
        sheet.open(R.drawable.ic_projector, act.getString(R.string.projection), act.getString(R.string.projection_sub), COLOR, null);
        sheet.section(act.getString(R.string.keystone));
        sheet.item(R.drawable.ic_projector, act.getString(R.string.keystone_manual), act.getString(R.string.keystone_manual_sub), v -> {
            sheet.close();
            KeystoneEditor.open(act, ui, host, home, prefs.accent(), this::noRoot);
        });
        sheet.item(0, act.getString(R.string.keystone_reset), act.getString(R.string.keystone_reset_sub), v ->
                Root.run("service call SurfaceFlinger 1050 f 0 f 0 f 0 f 0 f 0 f 0 f 0 f 0", (ok, out) ->
                        Toast.makeText(act, act.getString(ok ? R.string.keystone_reset_done : R.string.root_needed), Toast.LENGTH_SHORT).show()));
        if (hasHdmi(act)) {
            sheet.section(act.getString(R.string.source));
            sheet.item(R.drawable.ic_hdmi, act.getString(R.string.hdmi), act.getString(R.string.hdmi_sub), v -> openHdmi(act));
        }
        vendorRows();
        sheet.focus();
    }

    private void noRoot() {
        Toast.makeText(act, act.getString(R.string.projection_no_root), Toast.LENGTH_LONG).show();
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
}
