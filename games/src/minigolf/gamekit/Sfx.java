package gamekit;

import javax.microedition.media.Manager;

/**
 * Optional sound using the MIDP 2.0 tone generator. Every call is guarded:
 * if the handset has no tone support the game simply stays silent.
 */
public final class Sfx {
    public static boolean on = false;
    private static boolean broken;
    private static int[] seq;
    private static int seqPos, seqWait;

    private static final int[] WIN = { 72, 76, 79, 84 };
    private static final int[] LOSE = { 67, 63, 60, 55 };

    private Sfx() {}

    /** Play a MIDI note (60 = middle C) for ms milliseconds. */
    public static void tone(int note, int ms) {
        if (!on || broken) return;
        try {
            Manager.playTone(note, ms, 60);
        } catch (Throwable t) {
            broken = true;
        }
    }

    public static void click() { tone(84, 20); }
    public static void good() { tone(79, 40); }
    public static void bad() { tone(48, 80); }
    public static void hit() { tone(60, 30); }
    public static void win() { play(WIN); }
    public static void lose() { play(LOSE); }

    /** Queue a short melody; one note every 2 ticks. */
    public static void play(int[] notes) {
        seq = notes;
        seqPos = 0;
        seqWait = 0;
    }

    /** Called once per game tick by Game. */
    static void tick() {
        if (seq == null) return;
        if (seqWait > 0) { seqWait--; return; }
        tone(seq[seqPos], 90);
        seqPos++;
        seqWait = 1;
        if (seqPos >= seq.length) seq = null;
    }
}
