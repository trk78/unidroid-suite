package app.unidroid.remote;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pairing, like a TV app: the first time a phone opens the remote, the projector shows a
 * 4-digit code on screen and the phone types it. The phone is then trusted for good (its
 * id is kept); only its owner, who can see the TV, can pair it.
 */
final class Pairing {
    private static final File PAIRED = new File("/data/local/tmp/unidroid/paired");
    private static final long CODE_TTL = 3 * 60_000L;
    private static final int MAX_TRIES = 5;

    private static final class Pending {
        String code, name;
        long expires;
        int tries;
    }

    private final Set<String> paired = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Pending> pending = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    Pairing(Iterable<String> knownBefore) {
        try {
            if (PAIRED.isFile()) {
                for (String id : new String(Files.readAllBytes(PAIRED.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    if (!id.isEmpty()) paired.add(id);
                }
            } else {
                // First run with pairing: phones that already used the remote stay paired.
                for (String id : knownBefore) paired.add(id);
                save();
            }
        } catch (Exception ignored) {
            // Nobody paired yet.
        }
    }

    boolean paired(String id) {
        return id != null && paired.contains(id);
    }

    /** Shows (or shows again) this phone's code on the projector. */
    synchronized void ask(String id, String name) {
        Pending p = pending.get(id);
        long now = System.currentTimeMillis();
        if (p == null || p.expires < now || p.tries >= MAX_TRIES) {
            String before = p != null ? p.name : null;
            p = new Pending();
            p.name = before;
            p.code = String.format("%04d", random.nextInt(10_000));
            pending.put(id, p);
        }
        p.expires = now + CODE_TTL;
        if (name != null) p.name = name;
        Actions.exec("am", "start", "-n", Actions.LAUNCHER + "/.PairActivity",
                "--es", "code", p.code, "--es", "name", p.name == null ? "" : p.name);
    }

    /** True when the code is right: the phone is paired and the code leaves the screen. */
    synchronized boolean confirm(String id, String code) {
        Pending p = pending.get(id);
        if (p == null || p.expires < System.currentTimeMillis() || code == null) return false;
        if (!p.code.equals(code.trim())) {
            // Too many wrong codes: a new one replaces it on the TV.
            if (++p.tries >= MAX_TRIES) {
                p.tries = MAX_TRIES;
                ask(id, null);
            }
            return false;
        }
        pending.remove(id);
        paired.add(id);
        save();
        Actions.exec("am", "start", "-n", Actions.LAUNCHER + "/.PairActivity", "--ez", "done", "true");
        return true;
    }

    void forget(Iterable<String> ids) {
        for (String id : ids) paired.remove(id);
        save();
    }

    private void save() {
        try {
            synchronized (paired) {
                Files.write(PAIRED.toPath(), String.join("\n", paired).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Paired until the server restarts.
        }
    }
}
