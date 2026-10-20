package app.unidroid.remote;

import java.lang.reflect.Method;

/**
 * Picture and panel settings of Allwinner projectors, through the vendor HIDL service
 * vendor.aw.homlet.tvsystem.tvserver@1.0::ITvServer (the calls the HY300's own settings
 * app makes, found with dexdump). HwBinder is a system API: reached by reflection, which
 * works here because the server runs in app_process as root.
 */
final class TvServer {
    private static final String IFACE = "vendor.aw.homlet.tvsystem.tvserver@1.0::ITvServer";
    private static final int PQ_SET = 97, PQ_GET = 98, PANEL_SET = 127, PANEL_GET = 128;
    /** Picture settings: 1 brightness, 2 contrast, 3 saturation, 4 hue, 5 sharpness, 6 light output (0-100); 12 colour temperature (0-2). */
    private static final int[] PQ_TYPES = {1, 2, 3, 4, 5, 6, 12};
    /** Panel mirror: 0 front, 1 rear, 2 ceiling, 3 rear ceiling. */
    static final int PANEL_MIRROR = 3;

    private Object binder;

    static boolean isPicture(int type) {
        for (int t : PQ_TYPES) if (t == type) return true;
        return false;
    }

    static int max(int type) {
        return type == 12 ? 2 : 100;
    }

    synchronized int getPicture(int type) throws Exception {
        return call(PQ_GET, type);
    }

    synchronized int setPicture(int type, int value) throws Exception {
        return call(PQ_SET, type, value);
    }

    synchronized int getMirror() throws Exception {
        return call(PANEL_GET, PANEL_MIRROR);
    }

    synchronized int setMirror(int value) throws Exception {
        int r = call(PANEL_SET, PANEL_MIRROR, value);
        // The vendor app also keeps it in this property, read back at boot.
        Actions.exec("setprop", "persist.sys.panelvalue", String.valueOf(value));
        return r;
    }

    private int call(int code, int... args) throws Exception {
        try {
            return transact(code, args);
        } catch (Exception e) {
            binder = null; // service restarted: look it up again once
            return transact(code, args);
        }
    }

    private int transact(int code, int... args) throws Exception {
        Class<?> parcelClass = Class.forName("android.os.HwParcel");
        if (binder == null) {
            Method getService = Class.forName("android.os.HwBinder").getMethod("getService", String.class, String.class);
            binder = getService.invoke(null, IFACE, "default");
        }
        Object request = parcelClass.getConstructor().newInstance();
        parcelClass.getMethod("writeInterfaceToken", String.class).invoke(request, IFACE);
        Method writeInt = parcelClass.getMethod("writeInt32", int.class);
        for (int a : args) writeInt.invoke(request, a);
        Object reply = parcelClass.getConstructor().newInstance();
        Class.forName("android.os.IHwBinder").getMethod("transact", int.class, parcelClass, parcelClass, int.class)
                .invoke(binder, code, request, reply, 0);
        try {
            parcelClass.getMethod("verifySuccess").invoke(reply);
            return (Integer) parcelClass.getMethod("readInt32").invoke(reply);
        } finally {
            parcelClass.getMethod("release").invoke(reply);
        }
    }
}
