package app.unidroid.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.format.Formatter;

import java.io.BufferedReader;
import java.io.FileReader;

/** About this projector: model, versions, network, storage, memory, temperature and CPU speed. */
final class AboutPanel {
    private final Activity act;
    private final Sheet sheet;

    AboutPanel(Activity act, Sheet sheet) {
        this.act = act;
        this.sheet = sheet;
    }

    void open() {
        sheet.open(R.drawable.ic_settings, act.getString(R.string.about), Build.MANUFACTURER + " " + Build.MODEL, 0xFF8E8E93, null);

        sheet.section(act.getString(R.string.about_device));
        line(R.string.about_android, "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
        line(R.string.about_unidroid, SettingsPanel.version(act));
        String ip = RemoteLink.lanAddress();
        line(R.string.about_ip, ip != null ? ip : act.getString(R.string.wifi_not_connected));
        line(R.string.about_uptime, DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000));

        sheet.section(act.getString(R.string.about_resources));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        line(R.string.about_storage, act.getString(R.string.about_free_of,
                Formatter.formatShortFileSize(act, fs.getAvailableBytes()), Formatter.formatShortFileSize(act, fs.getTotalBytes())));
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mem);
        line(R.string.about_memory, act.getString(R.string.about_free_of,
                Formatter.formatShortFileSize(act, mem.availMem), Formatter.formatShortFileSize(act, mem.totalMem)));

        int temp = readInt("/sys/class/thermal/thermal_zone0/temp");
        if (temp > 0) line(R.string.about_temperature, act.getString(R.string.about_celsius, Math.round(temp / 1000f)));
        int cur = readInt("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
        int top = readInt("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
        if (cur > 0 && top > 0) {
            String speed = act.getString(R.string.about_mhz, cur / 1000, top / 1000);
            // Below 60 % of the top speed for a while means the chip is too hot and slowed down.
            Sheet.Row r = line(R.string.about_cpu, speed);
            if (cur < top * 0.6f && temp >= 70_000) r.value(speed + " · " + act.getString(R.string.about_throttled));
        }
        sheet.focus();
    }

    private Sheet.Row line(int label, String value) {
        return sheet.item(0, act.getString(label), value, null);
    }

    private static int readInt(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
