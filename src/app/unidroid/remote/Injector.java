package com.unidroid.remote;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.lang.reflect.Method;

/**
 * Injects key events straight into the system input pipeline (same approach as scrcpy).
 * Runs inside a long-lived app_process, so there is no per-key JVM start-up cost.
 */
final class Injector {
    private static final int INJECT_MODE_ASYNC = 0;

    private final Object manager;
    private final Method injectMethod;
    private final KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    Injector() throws ReflectiveOperationException {
        Class<?> cls;
        try {
            // Android 14+ moved the injection entry point here.
            cls = Class.forName("android.hardware.input.InputManagerGlobal");
        } catch (ClassNotFoundException e) {
            cls = Class.forName("android.hardware.input.InputManager");
        }
        Method getInstance = cls.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        manager = getInstance.invoke(null);
        injectMethod = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    boolean inject(InputEvent event) {
        try {
            return (Boolean) injectMethod.invoke(manager, event, INJECT_MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    void key(int action, int code, long downTime, int repeat, int flags) {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(downTime, now, action, code, repeat, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD));
    }

    void tap(int code) {
        long now = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, code, now, 0, 0);
        key(KeyEvent.ACTION_UP, code, now, 0, 0);
    }

    /** Types text: mapped characters as real key strokes, anything else (accents, emoji) as ACTION_MULTIPLE. */
    void type(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            String ch = text.substring(i, i + n);
            i += n;
            KeyEvent[] events = n == 1 ? charMap.getEvents(ch.toCharArray()) : null;
            if (events != null) {
                for (KeyEvent e : events) {
                    e.setSource(InputDevice.SOURCE_KEYBOARD);
                    inject(e);
                }
            } else {
                inject(new KeyEvent(SystemClock.uptimeMillis(), ch, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
            }
        }
    }

    /** Accepts "22", "DPAD_RIGHT" or "KEYCODE_DPAD_RIGHT". Returns -1 when invalid. */
    static int parseKeycode(String s) {
        if (s == null || s.isEmpty() || s.length() > 40) return -1;
        boolean numeric = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') numeric = false;
        }
        int code;
        if (numeric) {
            code = Integer.parseInt(s);
        } else {
            code = KeyEvent.keyCodeFromString(s.startsWith("KEYCODE_") ? s : "KEYCODE_" + s);
        }
        return code > 0 && code <= KeyEvent.getMaxKeyCode() ? code : -1;
    }
}
