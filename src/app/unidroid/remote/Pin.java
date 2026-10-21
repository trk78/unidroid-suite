package app.unidroid.remote;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Optional PIN for the remote. When set, a phone must enter it once; its device id is then
 * trusted until the PIN changes. Only a SHA-256 of the PIN is kept on the projector.
 */
final class Pin {
    private static final File HASH = new File("/data/local/tmp/unidroid/pin");
    private static final File TRUSTED = new File("/data/local/tmp/unidroid/pin_trusted");

    private String hash;
    private final Set<String> trusted = Collections.synchronizedSet(new HashSet<>());
    private long lastFailure;

    Pin() {
        try {
            if (HASH.isFile()) hash = new String(Files.readAllBytes(HASH.toPath()), StandardCharsets.UTF_8).trim();
            if (TRUSTED.isFile()) {
                for (String id : new String(Files.readAllBytes(TRUSTED.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    if (!id.isEmpty()) trusted.add(id);
                }
            }
        } catch (Exception ignored) {
            // No PIN.
        }
    }

    boolean required() {
        return hash != null && !hash.isEmpty();
    }

    /** Lets this phone in: no PIN set, already trusted, or the right PIN (it is then trusted). */
    synchronized boolean admit(String deviceId, String pin) {
        if (!required()) return true;
        if (deviceId != null && trusted.contains(deviceId)) return true;
        if (pin == null || pin.isEmpty()) return false;
        // At most one wrong guess per second.
        long now = System.currentTimeMillis();
        if (now - lastFailure < 1000) return false;
        if (!MessageDigest.isEqual(sha(pin).getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
            lastFailure = now;
            return false;
        }
        if (deviceId != null && deviceId.matches("[A-Za-z0-9-]{4,64}")) {
            trusted.add(deviceId);
            saveTrusted();
        }
        return true;
    }

    boolean trusted(String deviceId) {
        return !required() || (deviceId != null && trusted.contains(deviceId));
    }

    /** Sets a new 4-8 digit PIN (every phone must enter it again), or clears it with null. */
    synchronized boolean set(String pin) {
        try {
            trusted.clear();
            TRUSTED.delete();
            if (pin == null) {
                hash = null;
                HASH.delete();
                return true;
            }
            if (!pin.matches("[0-9]{4,8}")) return false;
            hash = sha(pin);
            Files.write(HASH.toPath(), hash.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveTrusted() {
        try {
            Files.write(TRUSTED.toPath(), String.join("\n", trusted).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Trusted until restart only.
        }
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("unidroid:" + s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
