package gamekit;

/**
 * Integer maths for CLDC 1.0 (no floating point). Angles use 256 units per
 * full turn; sin/cos return values scaled by 1024.
 */
public final class FMath {
    public static final int ONE = 1024;

    private static final short[] SIN = { 0, 25, 50, 75, 100, 125, 150, 175, 200, 224, 249, 273, 297, 321, 345,
            369, 392, 415, 438, 460, 483, 505, 526, 548, 569, 590, 610, 630, 650, 669, 688, 706, 724, 742, 759,
            775, 792, 807, 822, 837, 851, 865, 878, 891, 903, 915, 926, 936, 946, 955, 964, 972, 980, 987, 993,
            999, 1004, 1009, 1013, 1016, 1019, 1021, 1023, 1024, 1024 };

    private static final byte[] ATAN = { 0, 1, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22,
            23, 24, 25, 25, 26, 27, 28, 29, 29, 30, 31, 31, 32 };

    private FMath() {}

    /** sin(a) * 1024, a in 0..255 (wraps). */
    public static int sin(int a) {
        a &= 255;
        if (a < 64) return SIN[a];
        if (a < 128) return SIN[128 - a];
        if (a < 192) return -SIN[a - 128];
        return -SIN[256 - a];
    }

    public static int cos(int a) {
        return sin(a + 64);
    }

    /** Angle 0..255 of the vector (dx, dy); screen y grows downwards, 0 = +x, 64 = +y. */
    public static int atan2(int dy, int dx) {
        if (dx == 0 && dy == 0) return 0;
        int ax = Math.abs(dx), ay = Math.abs(dy);
        int a;
        if (ax >= ay) a = ATAN[(ay << 5) / ax];
        else a = 64 - ATAN[(ax << 5) / ay];
        if (dx < 0) a = 128 - a;
        if (dy < 0) a = 256 - a;
        return a & 255;
    }

    /** Integer square root. */
    public static int sqrt(int n) {
        if (n <= 0) return 0;
        int x = n, y = (x + 1) >> 1;
        while (y < x) {
            x = y;
            y = (x + n / x) >> 1;
        }
        return x;
    }

    public static int dist(int dx, int dy) {
        return sqrt(dx * dx + dy * dy);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Number of set bits (Integer.bitCount is not in CLDC 1.0). */
    public static int bits(int v) {
        int n = 0;
        while (v != 0) {
            v &= v - 1;
            n++;
        }
        return n;
    }

    public static int sign(int v) {
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }
}
