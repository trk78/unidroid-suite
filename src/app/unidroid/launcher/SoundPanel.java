package app.unidroid.launcher;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;

/** Native sound panel: volume, mute and interface sounds. */
final class SoundPanel {
    private static final int COLOR = 0xFF30D158;

    private final Activity act;
    private final Sheet sheet;
    private final BluetoothPanel bluetooth;

    SoundPanel(Activity act, Sheet sheet, BluetoothPanel bluetooth) {
        this.act = act;
        this.sheet = sheet;
        this.bluetooth = bluetooth;
    }

    void open() {
        AudioManager audio = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        sheet.open(R.drawable.ic_sound, act.getString(R.string.sound), act.getString(R.string.sound_sub), COLOR, null);

        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        String[] levels = new String[max + 1];
        for (int i = 0; i <= max; i++) levels[i] = Math.round(i * 100f / max) + " %";
        sheet.slider(0, act.getString(R.string.volume), levels, audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                i -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, i, 0));
        sheet.toggle(0, act.getString(R.string.mute), null, audio.isStreamMute(AudioManager.STREAM_MUSIC), on ->
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0));

        boolean clicks = Settings.System.getInt(act.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1;
        sheet.toggle(0, act.getString(R.string.ui_sounds), act.getString(R.string.ui_sounds_sub), clicks, on -> {
            // A system setting apps may not write: set it as root, then load or drop the sounds.
            Root.run("settings put system sound_effects_enabled " + (on ? 1 : 0), null);
            if (on) audio.loadSoundEffects();
            else audio.unloadSoundEffects();
        });

        sheet.section(act.getString(R.string.sound_output));
        sheet.item(R.drawable.ic_bluetooth, act.getString(R.string.bluetooth), act.getString(R.string.sound_bt_sub), v -> bluetooth.open());
        sheet.focus();
    }
}
