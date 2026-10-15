package app.unidroid.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native Wi-Fi panel: on/off, current network, scan, connect with a password, forget. */
final class WifiPanel {
    static final int PERMISSION_REQUEST = 41;
    private static final int COLOR = 0xFF4CC4FF;

    private final Activity act;
    private final Ui ui;
    private final Sheet sheet;
    private final WifiManager wifi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean receiving;
    private String passwordFor;

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, 400);
        }
    };
    private final Runnable refresh = this::render;

    WifiPanel(Activity act, Ui ui, Sheet sheet) {
        this.act = act;
        this.ui = ui;
        this.sheet = sheet;
        this.wifi = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    void open() {
        passwordFor = null;
        sheet.open(R.drawable.ic_wifi, act.getString(R.string.wifi), act.getString(R.string.wifi_sub), COLOR, this::stop);
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        act.registerReceiver(changes, f);
        receiving = true;
        ensurePermission();
        render();
        sheet.focus();
        wifi.startScan();
    }

    /** Scan results and the network name need the location permission on Android 8.1+. */
    private void ensurePermission() {
        if (hasPermission()) return;
        Root.check((ok, out) -> {
            if (ok) {
                Root.run("pm grant " + act.getPackageName() + " " + Manifest.permission.ACCESS_FINE_LOCATION, (g, o) -> {
                    wifi.startScan();
                    render();
                });
            } else if (Build.VERSION.SDK_INT >= 23) {
                act.requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST);
            }
        });
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23
                || act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void onPermissionResult() {
        if (sheet.isOpen()) {
            wifi.startScan();
            render();
        }
    }

    private void stop() {
        main.removeCallbacks(refresh);
        if (receiving) {
            act.unregisterReceiver(changes);
            receiving = false;
        }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        if (!sheet.isOpen() || passwordFor != null) return;
        int focusedIndex = sheet.focusedIndex();
        sheet.clear();

        boolean on = wifi.isWifiEnabled();
        sheet.toggle(R.drawable.ic_wifi, act.getString(R.string.wifi), act.getString(on ? R.string.wifi_on_sub : R.string.wifi_off_sub), on, enabled -> {
            if (!setEnabled(enabled)) toast(act.getString(R.string.action_failed));
        });
        if (!on) {
            restoreFocus(focusedIndex);
            return;
        }

        WifiInfo info = wifi.getConnectionInfo();
        String current = info != null ? unquote(info.getSSID()) : null;
        if (current != null && (current.isEmpty() || "<unknown ssid>".equals(current))) current = null;
        if (info != null && info.getNetworkId() != -1) {
            sheet.section(act.getString(R.string.wifi_connected));
            String name = current != null ? current : act.getString(R.string.wifi_hidden_name);
            String detail = ip(info.getIpAddress()) + " · " + info.getLinkSpeed() + " Mb/s";
            final int netId = info.getNetworkId();
            sheet.item(R.drawable.ic_wifi, name, detail, v -> confirmForget(name, netId)).trailing(bars(info.getRssi()));
        }

        sheet.section(act.getString(R.string.wifi_available));
        if (!hasPermission()) {
            sheet.note(act.getString(R.string.wifi_need_location));
        }
        Map<String, WifiConfiguration> saved = savedNetworks();
        List<ScanResult> results = bestPerSsid(hasPermission() ? wifi.getScanResults() : new ArrayList<>());
        int shown = 0;
        for (ScanResult r : results) {
            if (r.SSID.equals(current)) continue;
            String sec = security(r);
            boolean known = saved.containsKey(r.SSID);
            String value = (known ? act.getString(R.string.wifi_saved) + " · " : "")
                    + act.getString("open".equals(sec) ? R.string.wifi_open : R.string.wifi_secured);
            sheet.item(0, r.SSID, value, v -> connect(r, sec, saved.get(r.SSID))).trailing(bars(r.level));
            shown++;
        }
        if (shown == 0 && hasPermission()) sheet.note(act.getString(R.string.wifi_scanning));
        sheet.item(0, act.getString(R.string.wifi_rescan), null, v -> {
            wifi.startScan();
            toast(act.getString(R.string.wifi_scanning));
        });
        restoreFocus(focusedIndex);
    }

    private void restoreFocus(int index) {
        if (index >= 0) sheet.focusAt(index);
    }

    // ------------------------------------------------------------------ actions

    @SuppressWarnings("deprecation")
    private boolean setEnabled(boolean on) {
        if (wifi.setWifiEnabled(on)) return true;
        Root.run("svc wifi " + (on ? "enable" : "disable"), null);
        return true;
    }

    private void connect(ScanResult r, String sec, WifiConfiguration saved) {
        if (saved != null) {
            enable(saved.networkId, r.SSID);
            return;
        }
        if ("eap".equals(sec)) {
            toast(act.getString(R.string.wifi_enterprise));
            return;
        }
        if ("open".equals(sec)) {
            join(r.SSID, sec, null);
            return;
        }
        askPassword(r.SSID, sec);
    }

    private void askPassword(String ssid, String sec) {
        passwordFor = ssid;
        sheet.clear();
        sheet.section(ssid);
        EditText field = new EditText(act);
        field.setSingleLine(true);
        field.setHint(R.string.wifi_password);
        field.setTextColor(Ui.FG);
        field.setHintTextColor(Ui.DIM);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(28));
        field.setBackground(ui.glass(ui.px(18)));
        field.setPadding(ui.px(24), ui.px(22), ui.px(24), ui.px(22));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        Runnable go = () -> {
            String pass = field.getText().toString();
            if (pass.length() < 8) {
                toast(act.getString(R.string.wifi_password_short));
                return;
            }
            passwordFor = null;
            join(ssid, sec, pass);
            render();
        };
        field.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go.run();
                return true;
            }
            return false;
        });
        sheet.custom(field);
        final boolean[] shown = {false};
        sheet.toggle(0, act.getString(R.string.wifi_show_password), null, false, show -> {
            shown[0] = show;
            field.setInputType(InputType.TYPE_CLASS_TEXT | (show ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            field.setSelection(field.getText().length());
        });
        sheet.item(R.drawable.ic_wifi, act.getString(R.string.wifi_connect), null, v -> go.run());
        sheet.item(0, act.getString(R.string.cancel), null, v -> {
            passwordFor = null;
            render();
            sheet.focus();
        });
        field.requestFocus();
    }

    /** Adds and joins a network; falls back to the root shell for WPA3 or when Android refuses. */
    @SuppressWarnings("deprecation")
    private void join(String ssid, String sec, String pass) {
        toast(act.getString(R.string.wifi_connecting, ssid));
        if (!"wpa3".equals(sec)) {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = quote(ssid);
            if (pass == null) c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            else c.preSharedKey = quote(pass);
            int id = wifi.addNetwork(c);
            if (id != -1) {
                enable(id, ssid);
                return;
            }
        }
        String type = pass == null ? "open" : sec;
        Root.run("cmd wifi connect-network " + shellQuote(ssid) + " " + type + (pass == null ? "" : " " + shellQuote(pass)),
                (ok, out) -> {
                    if (!ok) toast(act.getString(R.string.wifi_connect_failed, ssid));
                });
    }

    @SuppressWarnings("deprecation")
    private void enable(int netId, String ssid) {
        if (wifi.enableNetwork(netId, true)) {
            wifi.reconnect();
        } else {
            toast(act.getString(R.string.wifi_connect_failed, ssid));
        }
    }

    private void confirmForget(String name, int netId) {
        sheet.clear();
        sheet.section(name);
        sheet.item(0, act.getString(R.string.wifi_forget), act.getString(R.string.wifi_forget_sub), v -> forget(netId));
        sheet.item(0, act.getString(R.string.cancel), null, v -> {
            render();
            sheet.focus();
        });
        sheet.focus();
    }

    @SuppressWarnings("deprecation")
    private void forget(int netId) {
        if (wifi.removeNetwork(netId)) {
            render();
            sheet.focus();
            return;
        }
        // Networks added by the system settings belong to it: only root can remove them.
        Root.run("cmd wifi forget-network " + netId, (ok, out) -> {
            if (!ok) toast(act.getString(R.string.root_needed));
            render();
            sheet.focus();
        });
    }

    // ------------------------------------------------------------------ helpers

    /** One line for tiles and other panels: "Wi-Fi Livebox-5D50", or "Wi-Fi off". */
    @SuppressWarnings("deprecation")
    static String summary(Context ctx) {
        WifiManager w = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (w == null || !w.isWifiEnabled()) return ctx.getString(R.string.wifi_off_short);
        WifiInfo info = w.getConnectionInfo();
        String ssid = info != null && info.getNetworkId() != -1 ? unquote(info.getSSID()) : null;
        if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid)) return ctx.getString(R.string.wifi_not_connected);
        return ctx.getString(R.string.wifi_line, ssid);
    }

    @SuppressWarnings("deprecation")
    private Map<String, WifiConfiguration> savedNetworks() {
        Map<String, WifiConfiguration> out = new LinkedHashMap<>();
        try {
            List<WifiConfiguration> list = wifi.getConfiguredNetworks();
            if (list != null) for (WifiConfiguration c : list) out.put(unquote(c.SSID), c);
        } catch (SecurityException ignored) {
            // No location permission yet.
        }
        return out;
    }

    private static List<ScanResult> bestPerSsid(List<ScanResult> all) {
        Map<String, ScanResult> best = new LinkedHashMap<>();
        for (ScanResult r : all) {
            if (r.SSID == null || r.SSID.isEmpty()) continue;
            ScanResult prev = best.get(r.SSID);
            if (prev == null || r.level > prev.level) best.put(r.SSID, r);
        }
        List<ScanResult> out = new ArrayList<>(best.values());
        Collections.sort(out, (a, b) -> b.level - a.level);
        return out;
    }

    private static String security(ScanResult r) {
        String c = r.capabilities == null ? "" : r.capabilities.toUpperCase(Locale.ROOT);
        if (c.contains("EAP")) return "eap";
        if (c.contains("SAE") && !c.contains("PSK")) return "wpa3";
        if (c.contains("PSK") || c.contains("WPA") || c.contains("RSN")) return "wpa2";
        if (c.contains("WEP")) return "eap"; // WEP: not offered, treated like unsupported
        return "open";
    }

    @SuppressWarnings("deprecation")
    private static String bars(int rssi) {
        int level = WifiManager.calculateSignalLevel(rssi, 4);
        return new String[] {"▂", "▂▄", "▂▄▆", "▂▄▆█"}[Math.max(0, Math.min(3, level))];
    }

    private static String ip(int addr) {
        return (addr & 0xFF) + "." + ((addr >> 8) & 0xFF) + "." + ((addr >> 16) & 0xFF) + "." + ((addr >> 24) & 0xFF);
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String unquote(String s) {
        if (s == null) return null;
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
