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
    /**
     * The Allwinner firmware declares HDMI1-3 and two CVBS inputs whatever the board has;
     * the HY300 only wires HDMI1. The live TV app reopens the last input (HDMI1) directly,
     * so HDMI skips the vendor's five-input picker, which only launches this app anyway.
     */
    private static final ComponentName HDMI_LIVE = new ComponentName("com.softwinner.awlivetv", "com.softwinner.awlivetv.MainActivity");
    private static final ComponentName HDMI_PICKER = new ComponentName("com.softwinner.awsource", "com.softwinner.awsource.MainActivity");

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

    private static ComponentName hdmi(Activity act) {
        for (ComponentName c : new ComponentName[] {HDMI_LIVE, HDMI_PICKER}) {
            if (new Intent().setComponent(c).resolveActivity(act.getPackageManager()) != null) return c;
        }
        return null;
    }

    static boolean hasHdmi(Activity act) {
        return hdmi(act) != null;
    }

    static void openHdmi(Activity act) {
        try {
            act.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(hdmi(act)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(act, act.getString(R.string.launch_failed, "HDMI"), Toast.LENGTH_SHORT).show();
        }
    }

    void open() {
        sheet.open(R.drawable.ic_projector, act.getString(R.string.projection), act.getString(R.string.projection_sub), COLOR, null);
        sheet.section(act.getString(R.string.keystone));
        sheet.item(R.drawable.ic_projector, act.getString(R.string.keystone_manual), act.getString(R.string.keystone_manual_sub), v -> {
            sheet.close();
            openKeystone();
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

    void openKeystone() {
        KeystoneEditor.open(act, ui, host, home, prefs.accent(), this::noRoot);
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
