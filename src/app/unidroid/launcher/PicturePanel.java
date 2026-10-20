package app.unidroid.launcher;

import android.app.Activity;
import android.widget.Toast;

/**
 * Native picture panel: brightness, contrast, saturation, hue, sharpness, light output and
 * colour temperature, through the projector's picture-quality service ({@link Tv}).
 * Left / Right change a value, which applies at once.
 */
final class PicturePanel {
    private static final int COLOR = 0xFFFFB020;
    private static final String[] TYPES = {Tv.BRIGHTNESS, Tv.CONTRAST, Tv.SATURATION, Tv.HUE, Tv.SHARPNESS, Tv.LIGHT, Tv.COLOR_TEMP};
    /** Factory values read on the HY300. */
    private static final int[] DEFAULTS = {50, 50, 50, 50, 50, 100, 0};
    /** Light output never goes below this: 0 would leave a black screen and no way to see the menu. */
    private static final int LIGHT_MIN = 30;

    private final Activity act;
    private final Sheet sheet;

    PicturePanel(Activity act, Sheet sheet) {
        this.act = act;
        this.sheet = sheet;
    }

    void open() {
        sheet.open(R.drawable.ic_image, act.getString(R.string.picture), act.getString(R.string.picture_sub), COLOR, null);
        sheet.note(act.getString(R.string.loading));
        Tv.read(TYPES, v -> {
            if (sheet.isOpen()) render(v);
        });
    }

    private void render(int[] v) {
        sheet.clear();
        if (v[0] < 0) {
            sheet.note(act.getString(R.string.picture_unavailable));
            return;
        }
        int[] labels = {R.string.brightness, R.string.contrast, R.string.saturation, R.string.hue, R.string.sharpness};
        for (int i = 0; i < labels.length; i++) percent(labels[i], TYPES[i], v[i], 0, 5);
        sheet.section(act.getString(R.string.picture_light));
        percent(R.string.light_output, Tv.LIGHT, v[5], LIGHT_MIN, 10).value(act.getString(R.string.light_output_sub));
        String[] temps = {act.getString(R.string.color_temp_1), act.getString(R.string.color_temp_2), act.getString(R.string.color_temp_3)};
        sheet.choice(0, act.getString(R.string.color_temp), temps, Math.max(0, v[6]), i -> Tv.write(Tv.COLOR_TEMP, i));
        sheet.section(act.getString(R.string.system));
        sheet.item(0, act.getString(R.string.picture_reset), act.getString(R.string.picture_reset_sub), x -> {
            for (int i = 0; i < TYPES.length; i++) Tv.write(TYPES[i], DEFAULTS[i]);
            Toast.makeText(act, act.getString(R.string.picture_reset_done), Toast.LENGTH_SHORT).show();
            open();
        });
        sheet.focus();
    }

    /** A 0-100 setting in steps, as a Left / Right choice. */
    private Sheet.Row percent(int label, String type, int value, int min, int step) {
        int n = (100 - min) / step + 1;
        String[] options = new String[n];
        for (int i = 0; i < n; i++) options[i] = (min + i * step) + " %";
        int index = Math.max(0, Math.min(n - 1, Math.round((Math.max(value, min) - min) / (float) step)));
        return sheet.slider(0, act.getString(label), options, index, i -> Tv.write(type, min + i * step));
    }
}
