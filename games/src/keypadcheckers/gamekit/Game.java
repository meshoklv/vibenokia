package gamekit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.rms.RecordStore;

/**
 * Shared game canvas. Provides the fixed-step loop, keypad input, the title,
 * help, pause and game-over screens, high-score persistence and sound.
 *
 * A game implements name(), help(), newGame(), update() and draw(Graphics),
 * and calls endGame(won) when a round is over.
 *
 * Loop: input -> update -> render -> sleep, one thread, integer maths only.
 */
public abstract class Game extends Canvas implements Runnable {
    // ----------------------------------------------------------- input bits
    public static final int K_UP = 1, K_DOWN = 2, K_LEFT = 4, K_RIGHT = 8, K_FIRE = 16;
    public static final int K_STAR = 32, K_POUND = 64, K_SOFT = 128;
    public static final int K_DIRS = K_UP | K_DOWN | K_LEFT | K_RIGHT;
    /** Digit key n is (K_NUM0 << n). */
    public static final int K_NUM0 = 1 << 8;
    public static final int K_DIGITS = 0x3FF << 8;
    public static final int K_ANY = 0x3FFFF;

    // ---------------------------------------------------------------- states
    public static final int TITLE = 0, HELP = 1, PLAY = 2, PAUSE = 3, OVER = 4;

    private static final int IT_PLAY = 0, IT_MODE = 1, IT_HELP = 2, IT_SOUND = 3, IT_EXIT = 4;
    private static final int PI_RESUME = 0, PI_RESTART = 1, PI_HELP = 2, PI_SOUND = 3, PI_MENU = 4;
    private static final String[] PAUSE_ITEMS = { "Resume", "Restart", "How to play", "Sound", "Main menu" };

    /** Keys held this tick (includes keys tapped during the tick). */
    protected int held;
    /** Keys newly pressed (or auto-repeated directions) since the last tick. */
    protected int pressed;
    /** Keys newly pressed since the last tick, excluding auto-repeat. */
    protected int tapped;
    private int latch, down, latchTap;

    /** Screen size, refreshed every tick and every paint. */
    protected int W, H;
    /** Ticks since the current round started. */
    protected int frame;
    /** Ticks since the application started (drives title animation). */
    protected int clock;
    /** Milliseconds per tick. Arcade games keep 50 (20 fps). */
    protected int tickMs = 50;
    protected int state = TITLE;
    protected int score;
    /** Index into modes(), chosen on the title screen. */
    protected int mode;
    protected boolean won;
    /** Optional custom text for the game-over panel ("Checkmate!", ...). */
    protected String headline;
    /** Free persistent slots for the game (levels unlocked, totals...). */
    protected final int[] saved = new int[16];

    /** Set if the game loop crashed; shown on screen and read by the test harness. */
    public static String crashed;

    private GameMIDlet midlet;
    private Thread thread;
    private volatile boolean running;
    private int menuSel, helpScroll, helpReturn, overTicks;
    private final int[] best = new int[8];
    private boolean newBest;
    private Vector helpLines;
    private int helpWidth;

    protected Game() {
        setFullScreenMode(true);
    }

    // ================================================================ hooks

    /** Display name of the game. */
    protected abstract String name();

    /** Help paragraphs (objective and controls). */
    protected abstract String[] help();

    /** Reset everything for a new round. score is already 0. */
    protected abstract void newGame();

    /** Advance one tick of gameplay. Read held/pressed. */
    protected abstract void update();

    /** Render the game. Also used as the backdrop of pause/game-over. */
    protected abstract void draw(Graphics g);

    /** Optional selectable modes (difficulty, board size, level...). */
    protected String[] modes() { return null; }

    /** Optional decoration drawn in the free area of the title screen. */
    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        Gfx.titleBlocks(g, x, y, w, h, accent(), clock);
    }

    /** Accent colour of the game's UI. */
    protected int accent() { return 0x33CC77; }

    /** Whether the round produces a score worth keeping. */
    protected boolean hasScore() { return true; }

    /** For time-based scores: smaller is better. */
    protected boolean lowerIsBetter() { return false; }

    /** Format a score for display (override for times, money...). */
    protected String formatScore(int s) { return String.valueOf(s); }

    /** Text under the title menu; defaults to the best score. */
    protected String footer() {
        return hasScore() && best() != 0 ? "Best: " + formatScore(best()) : "J2ME Game Archive";
    }

    /** Keys that open the pause menu during play. */
    protected int pauseKeys() { return K_STAR | K_SOFT; }

    // ======================================================= game helpers

    /** Finish the round. */
    protected void endGame(boolean didWin) {
        won = didWin;
        newBest = false;
        if (hasScore()) {
            int b = best[mode & 7];
            boolean better = lowerIsBetter() ? (didWin && (b == 0 || score < b)) : score > b;
            if (better) {
                best[mode & 7] = score;
                newBest = true;
            }
        }
        persist();
        overTicks = 0;
        state = OVER;
        if (didWin) Sfx.win(); else Sfx.lose();
    }

    /** Best score for the current mode (0 = none yet). */
    protected int best() { return best[mode & 7]; }

    /** Is digit key n newly pressed? */
    protected boolean digit(int n) { return (pressed & (K_NUM0 << n)) != 0; }

    /** First newly pressed digit 0..9, or -1. */
    protected int digitPressed() {
        for (int n = 0; n <= 9; n++) if ((pressed & (K_NUM0 << n)) != 0) return n;
        return -1;
    }

    /** Leave the game and return to the title screen (no score saved). */
    protected void quitToTitle() {
        state = TITLE;
        menuSel = 0;
    }

    /** Save the persistent data (best scores, sound flag, saved[]). */
    protected void persist() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeByte(1);
            out.writeBoolean(Sfx.on);
            for (int i = 0; i < best.length; i++) out.writeInt(best[i]);
            for (int i = 0; i < saved.length; i++) out.writeInt(saved[i]);
            byte[] b = bo.toByteArray();
            RecordStore rs = RecordStore.openRecordStore("gk", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Exception e) {
            // storage is optional; the game keeps working without it
        }
    }

    private void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore("gk", true);
            if (rs.getNumRecords() > 0) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                if (in.readByte() == 1) {
                    Sfx.on = in.readBoolean();
                    for (int i = 0; i < best.length; i++) best[i] = in.readInt();
                    for (int i = 0; i < saved.length; i++) saved[i] = in.readInt();
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
            // first run or no RMS
        }
    }

    // ============================================================ lifecycle

    void attach(GameMIDlet m) {
        midlet = m;
        load();
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    void stop() {
        running = false;
    }

    void systemPause() {
        if (state == PLAY) openPause();
    }

    protected void hideNotify() {
        if (state == PLAY) openPause();
        synchronized (this) {
            down = 0;
        }
    }

    public void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            if (crashed == null) {
                try {
                    step();
                } catch (Throwable t) {
                    crashed = t.toString();
                    t.printStackTrace();
                }
            }
            repaint();
            serviceRepaints();
            long sleep = tickMs - (System.currentTimeMillis() - t0);
            if (sleep < 5) sleep = 5;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    // ================================================================ input

    protected void keyPressed(int code) {
        int b = bits(code);
        synchronized (this) {
            latch |= b;
            latchTap |= b;
            down |= b;
        }
    }

    protected void keyReleased(int code) {
        int b = bits(code);
        synchronized (this) {
            down &= ~b;
        }
    }

    protected void keyRepeated(int code) {
        int b = bits(code) & K_DIRS;
        synchronized (this) {
            latch |= b;
        }
    }

    private int bits(int code) {
        if (code >= KEY_NUM0 && code <= KEY_NUM9) {
            int n = code - KEY_NUM0;
            int b = K_NUM0 << n;
            if (n == 2) b |= K_UP;
            else if (n == 8) b |= K_DOWN;
            else if (n == 4) b |= K_LEFT;
            else if (n == 6) b |= K_RIGHT;
            else if (n == 5) b |= K_FIRE;
            return b;
        }
        if (code == KEY_STAR) return K_STAR;
        if (code == KEY_POUND) return K_POUND;
        int ga = 0;
        try {
            ga = getGameAction(code);
        } catch (Exception e) {
            ga = 0;
        }
        switch (ga) {
            case UP: return K_UP;
            case DOWN: return K_DOWN;
            case LEFT: return K_LEFT;
            case RIGHT: return K_RIGHT;
            case FIRE: return K_FIRE;
            default: break;
        }
        if (code == -6 || code == -7 || code == -21 || code == -22 || code == 21 || code == 22) return K_SOFT;
        return 0;
    }

    // ================================================================= step

    private void step() {
        synchronized (this) {
            pressed = latch;
            latch = 0;
            tapped = latchTap;
            latchTap = 0;
            held = down | pressed;
        }
        W = getWidth();
        H = getHeight();
        clock++;
        Sfx.tick();
        switch (state) {
            case TITLE: updateTitle(); break;
            case HELP: updateHelp(); break;
            case PLAY:
                if ((pressed & pauseKeys()) != 0) {
                    openPause();
                } else {
                    frame++;
                    update();
                }
                break;
            case PAUSE: updatePause(); break;
            default: updateOver(); break;
        }
    }

    private void startGame() {
        score = 0;
        won = false;
        headline = null;
        frame = 0;
        newBest = false;
        Rnd.seed(System.currentTimeMillis());
        newGame();
        state = PLAY;
        pressed = 0;
    }

    private int[] titleItems() {
        String[] m = modes();
        int[] items = new int[m == null ? 4 : 5];
        int i = 0;
        items[i++] = IT_PLAY;
        if (m != null) items[i++] = IT_MODE;
        items[i++] = IT_HELP;
        items[i++] = IT_SOUND;
        items[i] = IT_EXIT;
        return items;
    }

    private String itemLabel(int it) {
        switch (it) {
            case IT_PLAY: return "Play";
            case IT_MODE: return "< " + modes()[mode] + " >";
            case IT_HELP: return "How to play";
            case IT_SOUND: return Sfx.on ? "Sound: On" : "Sound: Off";
            default: return "Exit";
        }
    }

    private void updateTitle() {
        int[] items = titleItems();
        if (menuSel >= items.length) menuSel = 0;
        String[] m = modes();
        if (m != null && mode >= m.length) mode = 0;
        if ((pressed & K_UP) != 0) { menuSel = (menuSel + items.length - 1) % items.length; Sfx.click(); }
        if ((pressed & K_DOWN) != 0) { menuSel = (menuSel + 1) % items.length; Sfx.click(); }
        int it = items[menuSel];
        if (it == IT_MODE && (pressed & (K_LEFT | K_RIGHT)) != 0) {
            mode = (mode + ((pressed & K_LEFT) != 0 ? m.length - 1 : 1)) % m.length;
            Sfx.click();
        }
        if ((pressed & K_POUND) != 0) openHelp(TITLE);
        if ((pressed & K_NUM0) != 0) toggleSound();
        if ((pressed & K_FIRE) != 0) {
            switch (it) {
                case IT_PLAY: startGame(); break;
                case IT_MODE: mode = (mode + 1) % m.length; Sfx.click(); break;
                case IT_HELP: openHelp(TITLE); break;
                case IT_SOUND: toggleSound(); break;
                default: if (midlet != null) midlet.exit(); break;
            }
        }
    }

    private void toggleSound() {
        Sfx.on = !Sfx.on;
        persist();
        Sfx.click();
    }

    private void openHelp(int from) {
        helpReturn = from;
        helpScroll = 0;
        state = HELP;
    }

    private void updateHelp() {
        if ((pressed & K_UP) != 0 && helpScroll > 0) helpScroll--;
        if ((pressed & K_DOWN) != 0) helpScroll++;
        if ((pressed & (K_FIRE | K_STAR | K_POUND | K_SOFT | K_LEFT)) != 0) {
            state = helpReturn;
            Sfx.click();
        }
    }

    private void openPause() {
        state = PAUSE;
        menuSel = 0;
    }

    private void updatePause() {
        if ((pressed & K_UP) != 0) { menuSel = (menuSel + PAUSE_ITEMS.length - 1) % PAUSE_ITEMS.length; Sfx.click(); }
        if ((pressed & K_DOWN) != 0) { menuSel = (menuSel + 1) % PAUSE_ITEMS.length; Sfx.click(); }
        if ((pressed & (K_STAR | K_SOFT)) != 0) { state = PLAY; return; }
        if ((pressed & K_FIRE) != 0) {
            switch (menuSel) {
                case PI_RESUME: state = PLAY; break;
                case PI_RESTART: startGame(); break;
                case PI_HELP: openHelp(PAUSE); break;
                case PI_SOUND: toggleSound(); break;
                default: quitToTitle(); break;
            }
        }
    }

    private void updateOver() {
        overTicks++;
        if (overTicks < 8) return;
        if ((pressed & K_FIRE) != 0) startGame();
        else if ((pressed & (K_STAR | K_SOFT | K_POUND)) != 0) quitToTitle();
    }

    // ================================================================ paint

    protected void paint(Graphics g) {
        W = getWidth();
        H = getHeight();
        g.setClip(0, 0, W, H);
        if (crashed != null) {
            g.setColor(0x400000);
            g.fillRect(0, 0, W, H);
            g.setColor(0xFFFFFF);
            g.setFont(Gfx.SMALL);
            Vector lines = Gfx.wrap("Error: " + crashed, Gfx.SMALL, W - 8);
            for (int i = 0; i < lines.size(); i++) {
                g.drawString((String) lines.elementAt(i), 4, 4 + i * Gfx.SMALL.getHeight(), Graphics.TOP | Graphics.LEFT);
            }
            return;
        }
        switch (state) {
            case TITLE: drawTitle(g); break;
            case HELP: drawHelp(g); break;
            case PLAY: draw(g); break;
            case PAUSE:
                draw(g);
                g.setClip(0, 0, W, H);
                drawPause(g);
                break;
            default:
                draw(g);
                g.setClip(0, 0, W, H);
                drawOver(g);
                break;
        }
    }

    private void drawTitle(Graphics g) {
        Gfx.backdrop(g, W, H, accent());
        Font tf = Gfx.fit(name(), W - 8);
        int ty = H < 140 ? 4 : H / 14;
        Gfx.shadowText(g, name(), W / 2, ty, Graphics.TOP | Graphics.HCENTER, tf, 0xFFFFFF, Gfx.shade(accent(), -60));
        int[] items = titleItems();
        Font mf = Gfx.SMALL_B;
        int lh = mf.getHeight() + 3;
        int menuH = items.length * lh + 4;
        int footH = Gfx.SMALL.getHeight() + 2;
        int menuY = H - footH - menuH - 2;
        int artY = ty + tf.getHeight() + 4;
        if (menuY - artY > 12) drawTitleArt(g, 4, artY, W - 8, menuY - artY - 4);
        g.setClip(0, 0, W, H);
        int bw = 0;
        for (int i = 0; i < items.length; i++) bw = Math.max(bw, mf.stringWidth(itemLabel(items[i])));
        bw = Math.min(W - 8, bw + 24);
        Gfx.panel(g, (W - bw) / 2, menuY, bw, menuH, 0x0B1220, Gfx.shade(accent(), -30));
        g.setFont(mf);
        for (int i = 0; i < items.length; i++) {
            int y = menuY + 3 + i * lh;
            if (i == menuSel) {
                g.setColor(accent());
                g.fillRect((W - bw) / 2 + 3, y - 1, bw - 6, lh - 1);
                g.setColor(0x0B1220);
            } else {
                g.setColor(0xD8E4EC);
            }
            g.drawString(itemLabel(items[i]), W / 2, y, Graphics.TOP | Graphics.HCENTER);
        }
        g.setFont(Gfx.SMALL);
        g.setColor(0x9FB2C0);
        g.drawString(footer(), W / 2, H - footH, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawHelp(Graphics g) {
        g.setColor(0x0B1220);
        g.fillRect(0, 0, W, H);
        Font f = Gfx.SMALL;
        int lh = f.getHeight() + 1;
        g.setColor(accent());
        g.fillRect(0, 0, W, lh + 4);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0x0B1220);
        g.drawString("HOW TO PLAY", W / 2, 2, Graphics.TOP | Graphics.HCENTER);
        if (helpLines == null || helpWidth != W) {
            helpWidth = W;
            helpLines = new Vector();
            String[] h = help();
            for (int i = 0; i < h.length; i++) {
                Vector part = Gfx.wrap(h[i], f, W - 10);
                for (int j = 0; j < part.size(); j++) helpLines.addElement(part.elementAt(j));
                helpLines.addElement("");
            }
            helpLines.addElement("* or soft key: pause menu");
        }
        int top = lh + 8, bottom = H - lh - 4;
        int visible = Math.max(1, (bottom - top) / lh);
        int maxScroll = Math.max(0, helpLines.size() - visible);
        if (helpScroll > maxScroll) helpScroll = maxScroll;
        g.setFont(f);
        for (int i = 0; i < visible && i + helpScroll < helpLines.size(); i++) {
            String s = (String) helpLines.elementAt(i + helpScroll);
            g.setColor(s.length() > 0 && s.charAt(0) == '-' ? accent() : 0xD8E4EC);
            g.drawString(s, 5, top + i * lh, Graphics.TOP | Graphics.LEFT);
        }
        g.setColor(0x9FB2C0);
        String foot = maxScroll > 0 ? "2/8 scroll  5 back" : "5 back";
        g.drawString(foot, W / 2, H - lh, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawPause(Graphics g) {
        Gfx.dim(g, 0, 0, W, H);
        Font f = Gfx.SMALL_B;
        int lh = f.getHeight() + 3;
        int bw = Math.min(W - 10, f.stringWidth("How to play") + 30);
        int bh = (PAUSE_ITEMS.length + 1) * lh + 8;
        int bx = (W - bw) / 2, by = (H - bh) / 2;
        Gfx.panel(g, bx, by, bw, bh, 0x0B1220, accent());
        g.setFont(f);
        g.setColor(accent());
        g.drawString("PAUSED", W / 2, by + 4, Graphics.TOP | Graphics.HCENTER);
        for (int i = 0; i < PAUSE_ITEMS.length; i++) {
            int y = by + 4 + (i + 1) * lh;
            String s = i == PI_SOUND ? (Sfx.on ? "Sound: On" : "Sound: Off") : PAUSE_ITEMS[i];
            if (i == menuSel) {
                g.setColor(accent());
                g.fillRect(bx + 3, y - 1, bw - 6, lh - 1);
                g.setColor(0x0B1220);
            } else {
                g.setColor(0xD8E4EC);
            }
            g.drawString(s, W / 2, y, Graphics.TOP | Graphics.HCENTER);
        }
    }

    private void drawOver(Graphics g) {
        Gfx.dim(g, 0, 0, W, H);
        String head = headline != null ? headline : (won ? "YOU WIN!" : "GAME OVER");
        Font hf = Gfx.fit(head, W - 16);
        Font f = Gfx.SMALL_B;
        int lh = f.getHeight() + 2;
        int lines = hasScore() ? 3 : 1;
        int bh = hf.getHeight() + lines * lh + 12;
        int bw = Math.min(W - 6, Math.max(hf.stringWidth(head), f.stringWidth("5 again  * menu")) + 16);
        int bx = (W - bw) / 2, by = (H - bh) / 2;
        Gfx.panel(g, bx, by, bw, bh, 0x0B1220, won ? 0x44DD66 : 0xDD4444);
        g.setFont(hf);
        g.setColor(won ? 0x66FF88 : 0xFF6655);
        g.drawString(head, W / 2, by + 4, Graphics.TOP | Graphics.HCENTER);
        int y = by + 6 + hf.getHeight();
        g.setFont(f);
        if (hasScore()) {
            g.setColor(0xFFFFFF);
            // a lost time-trial has no meaningful time to show
            String sc = lowerIsBetter() && !won ? "No time set" : "Score: " + formatScore(score);
            g.drawString(sc, W / 2, y, Graphics.TOP | Graphics.HCENTER);
            y += lh;
            g.setColor(newBest ? 0xFFDD44 : 0x9FB2C0);
            String b = newBest ? "NEW BEST!" : (best() != 0 ? "Best: " + formatScore(best()) : "");
            g.drawString(b, W / 2, y, Graphics.TOP | Graphics.HCENTER);
            y += lh;
        }
        if (overTicks >= 8) {
            g.setColor(0xD8E4EC);
            g.drawString("5 again  * menu", W / 2, y, Graphics.TOP | Graphics.HCENTER);
        }
    }
}
