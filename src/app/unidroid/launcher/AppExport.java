package app.unidroid.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Publishes the installed apps (label + icon) for the phone remote, whose custom buttons
 * can launch them. The remote server runs as root and reads files/remote/ directly:
 * apps.json and icons/&lt;package&gt;.png (96 px).
 */
final class AppExport {
    private static final int ICON_PX = 96;

    private AppExport() {
    }

    static void write(Context ctx, List<AppRepo.App> apps) {
        File dir = new File(ctx.getFilesDir(), "remote");
        File icons = new File(dir, "icons");
        new Thread(() -> {
            try {
                if (!icons.isDirectory() && !icons.mkdirs()) return;
                JSONArray list = new JSONArray();
                for (AppRepo.App a : apps) {
                    if (a.pkg().equals(ctx.getPackageName())) continue;
                    list.put(new JSONObject().put("pkg", a.pkg()).put("label", a.label));
                    File png = new File(icons, a.pkg() + ".png");
                    if (!png.exists() && a.icon != null) savePng(a.icon, png);
                }
                File tmp = new File(dir, "apps.json.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(list.toString().getBytes("UTF-8"));
                }
                tmp.renameTo(new File(dir, "apps.json"));
            } catch (Exception ignored) {
                // The remote just shows no apps to add.
            }
        }, "app-export").start();
    }

    private static void savePng(Drawable icon, File file) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
        d.setBounds(0, 0, ICON_PX, ICON_PX);
        d.draw(c);
        try (OutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bmp.recycle();
    }
}
