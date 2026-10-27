package at.greenchili.surfmate.bruecke;

/**
 * BLAKE2s-256 nach RFC 7693 (ohne Schlüssel, ohne Salt) – für Noise {@code _BLAKE2s}. Weder JCA noch Tink bieten
 * BLAKE2s an; die Funktion ist klein und gegen die RFC-Testwerte geprüft ({@code BrueckeNoiseTest}).
 */
final class Blake2s {

    static final int HASHLEN = 32, BLOCKLEN = 64;

    private static final int[] IV = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
    private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    private Blake2s() {}

    /** Hash über die Aneinanderreihung der Teile. */
    static byte[] hash(byte[]... teile) {
        int len = 0;
        for (byte[] t : teile) len += t.length;
        byte[] m = new byte[len];
        int o = 0;
        for (byte[] t : teile) {
            System.arraycopy(t, 0, m, o, t.length);
            o += t.length;
        }
        return hash(m);
    }

    static byte[] hash(byte[] m) {
        int[] h = IV.clone();
        h[0] ^= 0x01010000 ^ HASHLEN;   // Parameterblock: digest 32, key 0, fanout 1, depth 1
        long t = 0;
        int bloecke = Math.max(1, (m.length + BLOCKLEN - 1) / BLOCKLEN);
        int[] v = new int[16], w = new int[16];
        for (int b = 0; b < bloecke; b++) {
            boolean letzter = b == bloecke - 1;
            int start = b * BLOCKLEN;
            int n = Math.min(BLOCKLEN, m.length - start);
            t += n;
            for (int i = 0; i < 16; i++) {
                int x = 0;
                for (int j = 3; j >= 0; j--) {
                    int idx = start + i * 4 + j;
                    x = (x << 8) | (idx < start + n ? m[idx] & 0xff : 0);
                }
                w[i] = x;
            }
            System.arraycopy(h, 0, v, 0, 8);
            System.arraycopy(IV, 0, v, 8, 8);
            v[12] ^= (int) t;
            v[13] ^= (int) (t >>> 32);
            if (letzter) v[14] = ~v[14];
            for (int r = 0; r < 10; r++) {
                byte[] s = SIGMA[r];
                g(v, 0, 4, 8, 12, w[s[0]], w[s[1]]);
                g(v, 1, 5, 9, 13, w[s[2]], w[s[3]]);
                g(v, 2, 6, 10, 14, w[s[4]], w[s[5]]);
                g(v, 3, 7, 11, 15, w[s[6]], w[s[7]]);
                g(v, 0, 5, 10, 15, w[s[8]], w[s[9]]);
                g(v, 1, 6, 11, 12, w[s[10]], w[s[11]]);
                g(v, 2, 7, 8, 13, w[s[12]], w[s[13]]);
                g(v, 3, 4, 9, 14, w[s[14]], w[s[15]]);
            }
            for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
        }
        byte[] out = new byte[HASHLEN];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) h[i];
            out[i * 4 + 1] = (byte) (h[i] >>> 8);
            out[i * 4 + 2] = (byte) (h[i] >>> 16);
            out[i * 4 + 3] = (byte) (h[i] >>> 24);
        }
        return out;
    }

    private static void g(int[] v, int a, int b, int c, int d, int x, int y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
        v[a] = v[a] + v[b] + y;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
    }

    /** HMAC-BLAKE2s (RFC 2104, Blocklänge 64). */
    static byte[] hmac(byte[] key, byte[]... daten) {
        byte[] k = key.length > BLOCKLEN ? hash(key) : key;
        byte[] ipad = new byte[BLOCKLEN], opad = new byte[BLOCKLEN];
        for (int i = 0; i < BLOCKLEN; i++) {
            byte kb = i < k.length ? k[i] : 0;
            ipad[i] = (byte) (kb ^ 0x36);
            opad[i] = (byte) (kb ^ 0x5c);
        }
        byte[][] innen = new byte[daten.length + 1][];
        innen[0] = ipad;
        System.arraycopy(daten, 0, innen, 1, daten.length);
        return hash(opad, hash(innen));
    }
}
