package gamekit;

/** Tiny xorshift random generator (CLDC 1.0 Random lacks nextInt(n)). */
public final class Rnd {
    private static int s = 0x2545F491;

    private Rnd() {}

    public static void seed(long v) {
        s = (int) (v ^ (v >>> 32)) | 1;
    }

    public static int next() {
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        return s;
    }

    /** Uniform int in 0..n-1 (n > 0). */
    public static int nextInt(int n) {
        return (next() >>> 1) % n;
    }

    /** Uniform int in lo..hi inclusive. */
    public static int range(int lo, int hi) {
        return lo + nextInt(hi - lo + 1);
    }

    /** True with probability pct/100. */
    public static boolean chance(int pct) {
        return nextInt(100) < pct;
    }

    public static void shuffle(int[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
