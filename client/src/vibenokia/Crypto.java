package vibenokia;

/**
 * SHA-256 and HMAC-SHA256 implemented by hand.
 *
 * CLDC 1.1 has no cryptography at all (no javax.crypto, no MessageDigest), and
 * pulling in Bouncy Castle for two functions would add hundreds of KB to the JAR.
 * This is exactly what request signing needs: two methods, no dependencies.
 * Verified against reference values (RFC 4231 test vectors and Python hmac).
 */
final class Crypto {

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
        0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
        0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
        0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private Crypto() {}

    /** SHA-256 of arbitrary bytes. */
    static byte[] sha256(byte[] msg) {
        int[] h = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                   0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

        int origLen = msg.length;
        long bitLen = (long) origLen * 8;
        int padLen = 64 - (int) ((origLen + 9) % 64);
        if (padLen == 64) padLen = 0;
        byte[] data = new byte[origLen + 9 + padLen];
        System.arraycopy(msg, 0, data, 0, origLen);
        data[origLen] = (byte) 0x80;
        int off = data.length - 8;
        for (int i = 0; i < 8; i++)
            data[off + i] = (byte) ((bitLen >>> (56 - 8 * i)) & 0xff);

        int[] w = new int[64];
        for (int chunk = 0; chunk < data.length; chunk += 64) {
            for (int i = 0; i < 16; i++) {
                int j = chunk + i * 4;
                w[i] = ((data[j] & 0xff) << 24) | ((data[j + 1] & 0xff) << 16)
                     | ((data[j + 2] & 0xff) << 8) | (data[j + 3] & 0xff);
            }
            for (int i = 16; i < 64; i++) {
                int s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
                int s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
                w[i] = w[i - 16] + s0 + w[i - 7] + s1;
            }
            int a = h[0], b = h[1], c = h[2], d = h[3];
            int e = h[4], f = h[5], g = h[6], hh = h[7];
            for (int i = 0; i < 64; i++) {
                int S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                int ch = (e & f) ^ ((~e) & g);
                int t1 = hh + S1 + ch + K[i] + w[i];
                int S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                int maj = (a & b) ^ (a & c) ^ (b & c);
                int t2 = S0 + maj;
                hh = g; g = f; f = e; e = d + t1;
                d = c; c = b; b = a; a = t1 + t2;
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d;
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4]     = (byte) (h[i] >>> 24);
            out[i * 4 + 1] = (byte) (h[i] >>> 16);
            out[i * 4 + 2] = (byte) (h[i] >>> 8);
            out[i * 4 + 3] = (byte) h[i];
        }
        return out;
    }

    /** HMAC-SHA256 per RFC 2104. */
    static byte[] hmac(byte[] key, byte[] msg) {
        byte[] k = key;
        if (k.length > 64) k = sha256(k);
        byte[] ipad = new byte[64], opad = new byte[64];
        for (int i = 0; i < 64; i++) {
            byte kb = i < k.length ? k[i] : 0;
            ipad[i] = (byte) (kb ^ 0x36);
            opad[i] = (byte) (kb ^ 0x5c);
        }
        byte[] inner = new byte[64 + msg.length];
        System.arraycopy(ipad, 0, inner, 0, 64);
        System.arraycopy(msg, 0, inner, 64, msg.length);
        byte[] ih = sha256(inner);
        byte[] outer = new byte[64 + 32];
        System.arraycopy(opad, 0, outer, 0, 64);
        System.arraycopy(ih, 0, outer, 64, 32);
        return sha256(outer);
    }

    /** Lower-case hex string - the form in which the signature travels in the header. */
    static String hex(byte[] b) {
        StringBuffer sb = new StringBuffer(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            sb.append("0123456789abcdef".charAt(v >>> 4));
            sb.append("0123456789abcdef".charAt(v & 0x0f));
        }
        return sb.toString();
    }

    static byte[] utf8(String s) {
        try { return s.getBytes("UTF-8"); } catch (Exception e) { return s.getBytes(); }
    }

    static String hmacHex(String secret, String message) {
        return hex(hmac(utf8(secret), utf8(message)));
    }

    static String sha256Hex(byte[] data) { return hex(sha256(data)); }

    private static int rotr(int x, int n) { return (x >>> n) | (x << (32 - n)); }
}
