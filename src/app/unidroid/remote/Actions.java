package app.unidroid.remote;

import java.util.regex.Pattern;

/**
 * Named actions that are not key presses (open a screen, launch an app).
 * Only whitelisted actions exist; arguments are validated and passed as separate
 * process arguments, never through a shell.
 */
final class Actions {
    /** The UniDroid app (launcher side) provides the "apps" and "keystone" screens. */
    static final String LAUNCHER = "app.unidroid.launcher";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    private final Injector injector;

    Actions(Injector injector) {
        this.injector = injector;
    }

    /** Returns false when the action is unknown or its argument is invalid. */
    boolean run(String action) {
        if (action.equals("settings")) {
            return exec("am", "start", "-a", "android.settings.SETTINGS");
        }
        if (action.equals("apps")) {
            if (isInstalled(LAUNCHER)) {
                return exec("am", "start", "-n", LAUNCHER + "/.HomeActivity", "--es", "open", "apps");
            }
            injector.tap(3); // KEYCODE_HOME
            return true;
        }
        if (action.equals("keystone")) {
            return isInstalled(LAUNCHER) && exec("am", "start", "-n", LAUNCHER + "/.HomeActivity", "--es", "open", "keystone");
        }
        if (action.startsWith("launch:")) {
            String pkg = action.substring(7);
            if (!PACKAGE.matcher(pkg).matches()) return false;
            // Resolve the TV entry point (or the phone one), then start it explicitly:
            // launcher activities do not declare CATEGORY_DEFAULT, so an implicit start fails.
            return exec("sh", "-c",
                    "q() { cmd package query-activities --brief -a android.intent.action.MAIN -c \"$1\" \"$0\" | grep / | head -1; };"
                    + " c=$(q android.intent.category.LEANBACK_LAUNCHER); [ -z \"$c\" ] && c=$(q android.intent.category.LAUNCHER);"
                    + " [ -n \"$c\" ] && am start -n $c", pkg);
        }
        return false;
    }

    private static boolean isInstalled(String pkg) {
        return new java.io.File("/data/data/" + pkg).exists();
    }

    /** Runs a command in the background so the WebSocket loop never waits on it. */
    private static boolean exec(String... cmd) {
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                // Drain the output: closing the pipe early would kill `am` before it starts anything.
                byte[] sink = new byte[1024];
                while (p.getInputStream().read(sink) >= 0) { /* discard */ }
                p.waitFor();
            } catch (Exception ignored) {
                // Best effort.
            }
        }, "udr-action").start();
        return true;
    }
}
