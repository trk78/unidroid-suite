package app.unidroid.launcher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native Bluetooth panel: on/off, paired devices (connect, disconnect, forget), scan and pair. */
final class BluetoothPanel {
    private static final int COLOR = 0xFF5B7CFF;
    /** BluetoothProfile.HID_HOST, hidden from the SDK. */
    private static final int HID_HOST = 4;

    private final Activity act;
    private final Sheet sheet;
    private final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private boolean receiving;
    private boolean menuOpen;

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && d.getName() != null) found.put(d.getAddress(), d);
            }
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, 300);
        }
    };
    private final Runnable refresh = this::render;

    BluetoothPanel(Activity act, Sheet sheet) {
        this.act = act;
        this.sheet = sheet;
    }

    void open() {
        menuOpen = false;
        sheet.open(R.drawable.ic_bluetooth, act.getString(R.string.bluetooth), act.getString(R.string.bluetooth_sub), COLOR, this::stop);
        if (adapter == null) {
            sheet.note(act.getString(R.string.bt_missing));
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        act.registerReceiver(changes, f);
        receiving = true;
        ensurePermission();
        render();
        sheet.focus();
    }

    private void ensurePermission() {
        if (Build.VERSION.SDK_INT < 23
                || act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return;
        Root.check((ok, out) -> {
            if (ok) Root.run("pm grant " + act.getPackageName() + " " + Manifest.permission.ACCESS_FINE_LOCATION, null);
            else act.requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, WifiPanel.PERMISSION_REQUEST);
        });
    }

    private void stop() {
        main.removeCallbacks(refresh);
        if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        if (receiving) {
            act.unregisterReceiver(changes);
            receiving = false;
        }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        if (!sheet.isOpen() || menuOpen || adapter == null) return;
        int focused = sheet.focusedIndex();
        sheet.clear();

        boolean on = adapter.isEnabled();
        sheet.toggle(R.drawable.ic_bluetooth, act.getString(R.string.bluetooth),
                act.getString(on ? R.string.bt_on_sub : R.string.bt_off_sub), on, enable -> {
                    if (enable) adapter.enable();
                    else adapter.disable();
                });
        if (!on) {
            if (focused >= 0) sheet.focusAt(focused);
            return;
        }

        sheet.section(act.getString(R.string.bt_paired));
        List<BluetoothDevice> paired = new ArrayList<>(adapter.getBondedDevices());
        if (paired.isEmpty()) sheet.note(act.getString(R.string.bt_none_paired));
        for (BluetoothDevice d : paired) {
            boolean connected = isConnected(d);
            sheet.item(0, name(d), kind(d), v -> deviceMenu(d, connected))
                    .trailing(connected ? act.getString(R.string.bt_connected) : null);
            found.remove(d.getAddress());
        }

        sheet.section(act.getString(R.string.bt_nearby));
        for (BluetoothDevice d : new ArrayList<>(found.values())) {
            boolean bonding = d.getBondState() == BluetoothDevice.BOND_BONDING;
            sheet.item(0, name(d), kind(d), v -> {
                adapter.cancelDiscovery();
                if (!d.createBond()) toast(act.getString(R.string.action_failed));
            }).trailing(bonding ? act.getString(R.string.bt_pairing) : null);
        }
        boolean scanning = adapter.isDiscovering();
        if (found.isEmpty()) sheet.note(act.getString(scanning ? R.string.bt_searching : R.string.bt_search_hint));
        sheet.item(0, act.getString(scanning ? R.string.bt_searching : R.string.bt_search), null, v -> {
            found.clear();
            if (!adapter.isDiscovering()) adapter.startDiscovery();
            render();
        });
        if (focused >= 0) sheet.focusAt(focused);
    }

    private void deviceMenu(BluetoothDevice d, boolean connected) {
        menuOpen = true;
        sheet.clear();
        sheet.section(name(d));
        if (connected) {
            sheet.item(0, act.getString(R.string.bt_disconnect), null, v -> profiles(d, "disconnect"));
        } else {
            sheet.item(0, act.getString(R.string.bt_connect), null, v -> profiles(d, "connect"));
        }
        sheet.item(0, act.getString(R.string.bt_forget), act.getString(R.string.bt_forget_sub), v -> {
            if (!call(d, "removeBond")) toast(act.getString(R.string.action_failed));
            back();
        });
        sheet.item(0, act.getString(R.string.cancel), null, v -> back());
        sheet.focus();
    }

    private void back() {
        menuOpen = false;
        render();
        sheet.focus();
    }

    /** Connects or disconnects the audio and input profiles (hidden APIs, best effort). */
    private void profiles(BluetoothDevice d, String method) {
        toast(act.getString("connect".equals(method) ? R.string.bt_connecting : R.string.bt_disconnecting, name(d)));
        for (int profile : new int[] {BluetoothProfile.A2DP, HID_HOST}) {
            adapter.getProfileProxy(act, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int p, BluetoothProfile proxy) {
                    try {
                        Method m = proxy.getClass().getMethod(method, BluetoothDevice.class);
                        m.invoke(proxy, d);
                    } catch (Exception ignored) {
                        // Profile not supported by this device.
                    }
                    adapter.closeProfileProxy(p, proxy);
                }

                @Override
                public void onServiceDisconnected(int p) {
                }
            }, profile);
        }
        back();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isConnected(BluetoothDevice d) {
        try {
            return (Boolean) d.getClass().getMethod("isConnected").invoke(d);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean call(BluetoothDevice d, String method) {
        try {
            Object r = d.getClass().getMethod(method).invoke(d);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Exception e) {
            return false;
        }
    }

    private String name(BluetoothDevice d) {
        String n = d.getName();
        return n != null && !n.isEmpty() ? n : d.getAddress();
    }

    private String kind(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        if (c == null) return "";
        switch (c.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return act.getString(R.string.bt_kind_audio);
            case BluetoothClass.Device.Major.PERIPHERAL: return act.getString(R.string.bt_kind_input);
            case BluetoothClass.Device.Major.PHONE: return act.getString(R.string.bt_kind_phone);
            case BluetoothClass.Device.Major.COMPUTER: return act.getString(R.string.bt_kind_computer);
            default: return "";
        }
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
