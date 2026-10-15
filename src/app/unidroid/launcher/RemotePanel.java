package app.unidroid.launcher;

import android.app.Activity;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Remote panel: phones that use the remote, a QR code to add one, the projector's
 * Wi-Fi and Bluetooth, and the server itself.
 */
final class RemotePanel {
    private static final int COLOR = 0xFF0A84FF;

    private final Activity act;
    private final Ui ui;
    private final Sheet sheet;
    private final WifiPanel wifi;
    private final BluetoothPanel bluetooth;
    private final Runnable changed;

    RemotePanel(Activity act, Ui ui, Sheet sheet, WifiPanel wifi, BluetoothPanel bluetooth, Runnable changed) {
        this.act = act;
        this.ui = ui;
        this.sheet = sheet;
        this.wifi = wifi;
        this.bluetooth = bluetooth;
        this.changed = changed;
    }

    void open() {
        sheet.open(R.drawable.ic_remote, act.getString(R.string.remote), null, COLOR, null);
        sheet.note(act.getString(R.string.loading));
        RemoteLink.refresh(act, true, s -> {
            if (sheet.isOpen()) render(s);
        });
    }

    private void render(RemoteLink.State s) {
        int focused = sheet.focusedIndex();
        sheet.clear();
        sheet.subtitle(s.running ? s.url.substring(7) : act.getString(R.string.remote_server_off));

        sheet.section(act.getString(R.string.remote_devices));
        if (s.devices.isEmpty()) sheet.note(act.getString(R.string.remote_no_device));
        for (RemoteLink.Device d : s.devices) {
            sheet.item(0, d.name, describe(d), v -> confirmForget(d))
                    .trailing(d.connected ? act.getString(R.string.bt_connected) : null);
        }

        sheet.section(act.getString(R.string.remote_add));
        QrView qr = new QrView(act);
        qr.setText(s.url);
        FrameLayout box = new FrameLayout(act);
        box.addView(qr, new FrameLayout.LayoutParams(ui.px(300), ui.px(300), Gravity.START));
        sheet.custom(box);
        sheet.note(act.getString(R.string.remote_add_help, s.url.substring(7)));

        sheet.section(act.getString(R.string.row_projector));
        sheet.item(R.drawable.ic_wifi, act.getString(R.string.wifi), WifiPanel.summary(act), v -> wifi.open());
        sheet.item(R.drawable.ic_bluetooth, act.getString(R.string.bluetooth), BluetoothPanel.summary(act), v -> bluetooth.open());

        sheet.section(act.getString(R.string.remote_server));
        sheet.item(0, act.getString(R.string.remote_restart),
                s.running ? act.getString(R.string.remote_server_on, RemoteLink.PORT) : act.getString(R.string.remote_server_off), v -> {
                    Toast.makeText(act, act.getString(R.string.remote_starting), Toast.LENGTH_SHORT).show();
                    Root.run("for p in $(pidof unidroid-remote); do kill $p; done", (ok, out) -> open());
                });
        if (focused >= 0) sheet.focusAt(focused);
        else sheet.focus();
    }

    private String describe(RemoteLink.Device d) {
        String where = act.getString(d.installed ? R.string.remote_installed : R.string.remote_browser);
        if (d.connected) return where;
        CharSequence when = DateUtils.getRelativeTimeSpanString(d.seen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return where + " · " + when;
    }

    private void confirmForget(RemoteLink.Device d) {
        sheet.clear();
        sheet.section(d.name);
        sheet.item(0, act.getString(R.string.remote_forget), act.getString(R.string.remote_forget_sub), v ->
                RemoteLink.forget(d.id, () -> {
                    changed.run();
                    open();
                }));
        sheet.item(0, act.getString(R.string.cancel), null, v -> open());
        sheet.focus();
    }
}
