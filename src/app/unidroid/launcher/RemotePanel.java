package app.unidroid.launcher;

import android.app.Activity;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Remote panel: phones that use the remote (each can be forgotten), a QR code to add
 * one, and the server itself.
 */
final class RemotePanel {
    private static final int COLOR = 0xFF0A84FF;

    private final Activity act;
    private final Ui ui;
    private final Sheet sheet;
    private final Runnable changed;

    RemotePanel(Activity act, Ui ui, Sheet sheet, Runnable changed) {
        this.act = act;
        this.ui = ui;
        this.sheet = sheet;
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
            sheet.item(R.drawable.ic_remote, d.name, describe(d), v -> confirmForget(d))
                    .trailing(act.getString(R.string.remote_forget_short));
        }

        sheet.section(act.getString(R.string.remote_add));
        QrView qr = new QrView(act);
        qr.setText(s.url);
        FrameLayout box = new FrameLayout(act);
        box.addView(qr, new FrameLayout.LayoutParams(ui.px(300), ui.px(300), Gravity.START));
        sheet.custom(box);
        sheet.note(act.getString(R.string.remote_add_help, s.url.substring(7)));

        sheet.section(act.getString(R.string.remote_security));
        sheet.item(0, act.getString(R.string.pin), act.getString(s.pin ? R.string.pin_on : R.string.pin_off), v -> editPin(s.pin));

        sheet.section(act.getString(R.string.remote_server));
        sheet.item(0, act.getString(R.string.remote_restart),
                s.running ? act.getString(R.string.remote_server_on, RemoteLink.PORT) : act.getString(R.string.remote_server_off), v -> {
                    Toast.makeText(act, act.getString(R.string.remote_starting), Toast.LENGTH_SHORT).show();
                    Root.run("for p in $(pidof unidroid-remote); do kill $p; done", (ok, out) -> open());
                });
        if (focused >= 0) sheet.focusAt(focused);
        else sheet.focus();
    }

    /** Four digits picked with Left / Right: easy with the TV remote, no keyboard needed. */
    private void editPin(boolean isSet) {
        sheet.clear();
        sheet.section(act.getString(R.string.pin));
        sheet.note(act.getString(R.string.pin_help));
        String[] digits = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
        int[] pin = new int[4];
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < pin.length; i++) {
            pin[i] = r.nextInt(10);
            final int at = i;
            sheet.choice(0, act.getString(R.string.pin_digit, i + 1), digits, pin[i], d -> pin[at] = d);
        }
        sheet.item(0, act.getString(R.string.pin_save), null, v -> {
            StringBuilder code = new StringBuilder();
            for (int d : pin) code.append(d);
            RemoteLink.call("/pin?set=" + code);
            Toast.makeText(act, act.getString(R.string.pin_saved, code), Toast.LENGTH_LONG).show();
            sheet.close();
        });
        if (isSet) {
            sheet.item(0, act.getString(R.string.pin_remove), act.getString(R.string.pin_remove_sub), v -> {
                RemoteLink.call("/pin?clear=1");
                sheet.close();
            });
        }
        sheet.item(0, act.getString(R.string.cancel), null, v -> open());
        sheet.focus();
    }

    private String describe(RemoteLink.Device d) {
        String where = act.getString(d.installed ? R.string.remote_installed : R.string.remote_browser);
        if (d.connected) return where + " · " + act.getString(R.string.bt_connected);
        CharSequence when = DateUtils.getRelativeTimeSpanString(d.seen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return where + " · " + when;
    }

    private void confirmForget(RemoteLink.Device d) {
        sheet.clear();
        sheet.section(d.name);
        sheet.note(describe(d));
        sheet.item(0, act.getString(R.string.remote_forget), act.getString(R.string.remote_forget_sub), v ->
                RemoteLink.forget(d.id, () -> {
                    changed.run();
                    open();
                }));
        sheet.item(0, act.getString(R.string.cancel), null, v -> open());
        sheet.focus();
    }
}
