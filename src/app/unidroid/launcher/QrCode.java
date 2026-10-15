package app.unidroid.launcher;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR Code encoder for short texts such as the remote's address: byte mode,
 * error correction level M, versions 1 to 10 (up to 213 bytes). Follows ISO/IEC 18004;
 * the structure mirrors Project Nayuki's reference design, rewritten from the standard.
 */
final class QrCode {
    // Error correction level M, index = version.
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_LEVEL_M = 0;

    final int size;
    private final boolean[][] modules;
    private final boolean[][] function;

    /** Encodes text; throws IllegalArgumentException when it is too long. */
    static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int version = 1; version <= 10; version++) {
            int capacityBits = dataCodewords(version) * 8;
            int needed = 4 + (version < 10 ? 8 : 16) + data.length * 8;
            if (needed <= capacityBits) return new QrCode(version, data);
        }
        throw new IllegalArgumentException("text too long for a QR code");
    }

    /** true = dark module. */
    boolean get(int x, int y) {
        return modules[y][x];
    }

    // ------------------------------------------------------------------ build

    private QrCode(int version, byte[] data) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
        drawFunctionPatterns(version);
        drawCodewords(addEccAndInterleave(version, dataCodewords(version, data)));

        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormatBits(mask);
            long p = penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = mask;
            }
            applyMask(mask); // undo (XOR)
        }
        applyMask(best);
        drawFormatBits(best);
    }

    private static int rawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    /** Mode, length, bytes, terminator and padding, as data codewords. */
    private static byte[] dataCodewords(int ver, byte[] data) {
        int capacity = dataCodewords(ver);
        BitBuffer bb = new BitBuffer(capacity * 8);
        bb.append(0b0100, 4);
        bb.append(data.length, ver < 10 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        return bb.bytes;
    }

    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int numBlocks = BLOCKS[ver];
        int eccLen = ECC_PER_BLOCK[ver];
        int raw = rawDataModules(ver) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;

        byte[] divisor = rsDivisor(eccLen);
        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int dataLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[dataLen];
            System.arraycopy(data, k, dat, 0, dataLen);
            k += dataLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            // Short blocks keep a hole at shortLen - eccLen, skipped when interleaving.
            System.arraycopy(dat, 0, block, 0, dataLen);
            System.arraycopy(ecc, 0, block, shortLen + 1 - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] result = new byte[raw];
        int k = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - eccLen || j >= numShort) result[k++] = blocks[j][i];
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ Reed-Solomon over GF(256)

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) mul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = mul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ------------------------------------------------------------------ modules

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] align = alignmentPositions(ver);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0);
                if (!corner) alignment(align[i], align[j]);
            }
        }
        drawFormatBits(0); // reserves the area, rewritten once the mask is chosen
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, bit);
                set(b, a, bit);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, dist != 2 && dist != 4);
            }
        }
    }

    private void alignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] result = new int[n];
        result[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_LEVEL_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true); // always-dark module
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (function[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= invert;
            }
        }
    }

    /** Simplified penalty (runs, 2×2 blocks, balance): enough to pick a readable mask. */
    private long penalty() {
        long result = 0;
        for (int y = 0; y < size; y++) {
            int run = 1;
            for (int x = 1; x <= size; x++) {
                if (x < size && modules[y][x] == modules[y][x - 1]) run++;
                else {
                    if (run >= 5) result += run - 2;
                    run = 1;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            int run = 1;
            for (int y = 1; y <= size; y++) {
                if (y < size && modules[y][x] == modules[y - 1][x]) run++;
                else {
                    if (run >= 5) result += run - 2;
                    run = 1;
                }
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + Math.max(0, k) * 10L;
    }

    private static final class BitBuffer {
        final byte[] bytes;
        int length;

        BitBuffer(int capacityBits) {
            bytes = new byte[(capacityBits + 7) / 8];
        }

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--, length++) {
                if (((value >>> i) & 1) != 0) bytes[length >>> 3] |= (byte) (0x80 >>> (length & 7));
            }
        }
    }
}
