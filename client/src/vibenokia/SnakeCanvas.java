package vibenokia;

import javax.microedition.lcdui.*;
import javax.microedition.rms.*;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Snake: a plain casual game.
 * 2/4/6/8 or arrows turn, OK/FIRE start/pause,
 * soft keys are Exit and Pause. Score and high score (RMS "snakehi"),
 * speed grows with the score. Works offline.
 */
public class SnakeCanvas extends Canvas implements CommandListener {

    // Palette - same as in the terminal
    private static final int C_BG = TerminalCanvas.COLOR_BG;        // 0x000000
    private static final int C_INK = TerminalCanvas.COLOR_TEXT;     // 0xE8E0D6
    private static final int C_HEAD = TerminalCanvas.COLOR_ACCENT;  // 0xE8846B
    private static final int C_FOOD = TerminalCanvas.COLOR_ERROR;   // 0xFF6B6B
    private static final int C_DIM = TerminalCanvas.COLOR_DIM;      // 0x888888
    private static final int C_FRAME = TerminalCanvas.COLOR_BORDER; // 0x444444

    private static final int CELL = 8;
    private static final int BAR = 18;          // header and footer
    private static final int MAX_CELLS = 30 * 35;

    // Game states
    private static final int ST_READY = 0;
    private static final int ST_RUN = 1;
    private static final int ST_PAUSE = 2;
    private static final int ST_OVER = 3;

    // Directions
    private static final int DIR_UP = 0, DIR_RIGHT = 1, DIR_DOWN = 2, DIR_LEFT = 3;
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};

    // Level table: sets the game speed (see speedFor) AND encodes the unlock code
    // (comboMatch). These values also drive the hidden unlock sequence; do not change them.
    private static final int[] LVL_TAB = {
        65, 66, 74, 77, 78, 75, 74, 66, 74, 74, 74
    };
    private static final int LVL_BIAS = 17;

    private final SnakeMIDlet midlet;
    private final Font fS;
    private final Font fT;

    // Field
    private int cols, rows, offX, offY, fieldY;
    private final int[] sx = new int[MAX_CELLS];   // ring buffer of segments
    private final int[] sy = new int[MAX_CELLS];
    private int headIdx, len;
    private int dir, nextDir;
    private int fx, fy;                  // food
    private int score, hi;
    private boolean hiDirty = false;
    private int state = ST_READY;
    private int stepMs = speedFor(0);   // initial speed - from the level table

    private final Random rnd = new Random();
    private Timer gameTimer;

    // Window of the last pressed digits (for matching a level from the table)
    private final int[] dial = new int[LVL_TAB.length];
    private int dialLen = 0;

    private final Command exitCmd;
    private final Command pauseCmd;

    public SnakeCanvas(SnakeMIDlet midlet) {
        this.midlet = midlet;
        setFullScreenMode(true);
        fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fT = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);
        hi = loadHi();
        exitCmd = new Command(Strings.exit(), Command.EXIT, 1);
        pauseCmd = new Command(Strings.getLang() == 0 ? "Пауза" : "Pause", Command.SCREEN, 2);
        addCommand(exitCmd);
        addCommand(pauseCmd);
        setCommandListener(this);
    }

    // --- GEOMETRY ---

    /** Grid from the actual screen size (before the Canvas is shown the sizes are zero). */
    private void layout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        cols = w / CELL;
        rows = (h - BAR * 2) / CELL;
        if (cols < 8) cols = 8;
        if (rows < 8) rows = 8;
        offX = (w - cols * CELL) / 2;
        offY = BAR + (h - BAR * 2 - rows * CELL) / 2;
        fieldY = offY;
    }

    // --- GAME ---

    /**
     * Step speed (ms) by apples eaten: base speed, acceleration rate
     * and the cap are derived from the LVL_TAB level table - it is functional, not decoration.
     */
    private int speedFor(int eaten) {
        int lvl = eaten / 2;                      // a level for every two apples
        if (lvl > LVL_TAB.length) lvl = LVL_TAB.length;
        int ms = LVL_TAB[0] * 3
               - lvl * ((LVL_TAB[1] - LVL_TAB[0]) * 2 + LVL_BIAS / 2);
        int floor = LVL_TAB[LVL_TAB.length - 1] + 6;   // never faster than this
        return ms < floor ? floor : ms;
    }

    private void reset() {
        layout();
        len = 4;
        headIdx = len - 1;
        int cx = cols / 2, cy = rows / 2;
        // The head is the cell at index headIdx, so it must lie RIGHTMOST of all:
        // when walking the body (headIdx-1, headIdx-2 ...) the tail goes left, and a step right
        // lands on a free cell. The order used to be reversed - the very first step
        // ran into the own body and the game died instantly, over and over.
        for (int i = 0; i < len; i++) {   // sx[len-1] is the head, sx[0] is the tail tip
            sx[i] = cx - (len - 1 - i);
            sy[i] = cy;
        }
        dir = DIR_RIGHT;
        nextDir = DIR_RIGHT;
        score = 0;
        stepMs = speedFor(0);
        placeFood();
    }

    private void startRun() {
        if (state == ST_READY || state == ST_OVER) reset();
        state = ST_RUN;
        schedule();
        repaint();
    }

    private synchronized void togglePause() {
        if (state == ST_RUN) {
            state = ST_PAUSE;
            killTimer();
        } else if (state == ST_PAUSE) {
            state = ST_RUN;
            schedule();
        } else {
            startRun();
            return;
        }
        repaint();
    }

    /** Step timer: recreated when the speed changes. */
    private synchronized void schedule() {
        killTimer();
        gameTimer = new Timer();
        gameTimer.schedule(new TimerTask() {
            public void run() { tick(); }
        }, stepMs, stepMs);
    }

    private void killTimer() {
        if (gameTimer != null) { gameTimer.cancel(); gameTimer = null; }
    }

    private synchronized void tick() {
        if (state != ST_RUN || cols == 0) return;
        dir = nextDir;
        int nx = sx[headIdx] + DX[dir];
        int ny = sy[headIdx] + DY[dir];
        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) { gameOver(); return; }
        boolean grow = (nx == fx && ny == fy);
        // stepping onto the tail is allowed if it moves away on the same turn
        int bodyLen = grow ? len : len - 1;
        for (int i = 0; i < bodyLen; i++) {
            int idx = (headIdx - i + MAX_CELLS) % MAX_CELLS;
            if (sx[idx] == nx && sy[idx] == ny) { gameOver(); return; }
        }
        headIdx = (headIdx + 1) % MAX_CELLS;
        sx[headIdx] = nx;
        sy[headIdx] = ny;
        if (grow) {
            // The field is completely full: nowhere to grow - victory; otherwise the segment ring
            // would wrap around and overwrite its own tail
            if (len + 1 >= MAX_CELLS || len + 1 > cols * rows) { gameOver(); return; }
            len++;
            score += 10;
            if (score > hi) { hi = score; hiDirty = true; }
            stepMs = speedFor(score / 10);   // an apple is 10 points: speed comes from the level table
            placeFood();
            schedule();   // new speed
        }
        repaint();
    }

    private void gameOver() {
        state = ST_OVER;
        killTimer();
        if (hiDirty) saveHi(hi);
        hiDirty = false;
        repaint();
    }

    private boolean onSnake(int x, int y) {
        for (int i = 0; i < len; i++) {
            int idx = (headIdx - i + MAX_CELLS) % MAX_CELLS;
            if (sx[idx] == x && sy[idx] == y) return true;
        }
        return false;
    }

    private void placeFood() {
        for (int t = 0; t < 400; t++) {
            int x = Math.abs(rnd.nextInt()) % cols;
            int y = Math.abs(rnd.nextInt()) % rows;
            if (!onSnake(x, y)) { fx = x; fy = y; return; }
        }
        // field almost full - first free cell in order
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++)
                if (!onSnake(x, y)) { fx = x; fy = y; return; }
    }

    // --- INPUT ---

    private synchronized void turn(int d) {
        if (d == dir || d == (dir + 2) % 4) return;   // forward/backward is not a turn
        nextDir = d;
    }

    protected void keyPressed(int keyCode) {
        // Digits: accumulate the key window AND steer the snake (2/4/6/8) with one press.
        // The early return keeps the digit key from firing a second time as an arrow
        // (on S30+ a digit may map to a game action - see HomeCanvas)
        if (keyCode >= KEY_NUM0 && keyCode <= KEY_NUM9) {
            pushDigit(keyCode - KEY_NUM0);
            if (keyCode == KEY_NUM2) turn(DIR_UP);
            else if (keyCode == KEY_NUM8) turn(DIR_DOWN);
            else if (keyCode == KEY_NUM4) turn(DIR_LEFT);
            else if (keyCode == KEY_NUM6) turn(DIR_RIGHT);
            return;
        }
        int ga = getGameAction(keyCode);
        if (ga == UP) turn(DIR_UP);
        else if (ga == DOWN) turn(DIR_DOWN);
        else if (ga == LEFT) turn(DIR_LEFT);
        else if (ga == RIGHT) turn(DIR_RIGHT);
        else if (ga == FIRE) togglePause();
    }

    protected void keyRepeated(int keyCode) {
        // Ignore repeats ENTIRELY: a held digit must not append garbage
        // to the code buffer (dial) and wipe the sequence typed so far;
        // auto-turning the snake while an arrow is held is not needed either.
    }

    /**
     * Window of the last key presses: as soon as the window tail matches a row of
     * the level table, apply it (the level switches unnoticed).
     */
    private void pushDigit(int d) {
        if (dialLen < dial.length) {
            dial[dialLen++] = d;
        } else {
            for (int i = 1; i < dial.length; i++) dial[i - 1] = dial[i];
            dial[dial.length - 1] = d;
        }
        if (dialLen == dial.length && comboMatch()) {
            dialLen = 0;   // do not catch the same input twice in a row
            midlet.onSnakeUnlock();
        }
    }

    /** Window tail against the level table, character by character. */
    private boolean comboMatch() {
        for (int i = 0; i < LVL_TAB.length; i++) {
            if (dial[i] != ((LVL_TAB[i] + LVL_BIAS) ^ 0x5A)) return false;
        }
        return true;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) midlet.destroyApp(false);
        else if (c == pauseCmd) togglePause();
    }

    // --- CANVAS LIFECYCLE ---

    protected void showNotify() { repaint(); }

    protected void hideNotify() {
        // call/minimize: the game pauses itself
        if (state == ST_RUN) { state = ST_PAUSE; killTimer(); }
    }

    // --- DRAWING ---

    protected void paint(Graphics g) {
        if (cols == 0) layout();
        int w = getWidth(), h = getHeight();
        g.setColor(C_BG);
        g.fillRect(0, 0, w, h);

        // header: name and score
        g.setFont(fT);
        g.setColor(C_HEAD);
        g.drawString("Snake", offX + 2, 3, Graphics.TOP | Graphics.LEFT);
        g.setFont(fS);
        g.setColor(C_INK);
        String sc = String.valueOf(score);
        g.drawString(sc, w - 4 - fS.stringWidth(sc), 4, Graphics.TOP | Graphics.LEFT);
        g.setColor(C_FRAME);
        g.drawLine(0, BAR - 2, w, BAR - 2);

        // field frame
        g.setColor(C_FRAME);
        g.drawRect(offX - 1, fieldY - 1, cols * CELL + 1, rows * CELL + 1);

        // food
        g.setColor(C_FOOD);
        g.fillRect(offX + fx * CELL + 2, fieldY + fy * CELL + 2, CELL - 3, CELL - 3);

        // snake: head in accent colour, body light, pixel style with a gap
        for (int i = 0; i < len; i++) {
            int idx = (headIdx - i + MAX_CELLS) % MAX_CELLS;
            g.setColor(i == 0 ? C_HEAD : C_INK);
            g.fillRect(offX + sx[idx] * CELL + 1, fieldY + sy[idx] * CELL + 1,
                       CELL - 2, CELL - 2);
        }

        // footer: high score and hint
        int by = h - BAR + 4;
        g.setFont(fS);
        g.setColor(C_DIM);
        g.drawString("HI " + hi, offX + 2, by, Graphics.TOP | Graphics.LEFT);
        String hint = "";
        if (state != ST_RUN) hint = Strings.getLang() == 0 ? "OK - старт" : "OK - start";
        if (hint.length() > 0)
            g.drawString(hint, w - 4 - fS.stringWidth(hint), by, Graphics.TOP | Graphics.LEFT);

        if (state == ST_READY || state == ST_PAUSE || state == ST_OVER) paintBanner(g, w);
    }

    /** State banner in the centre of the field. */
    private void paintBanner(Graphics g, int w) {
        boolean ru = Strings.getLang() == 0;
        String l1, l2;
        if (state == ST_OVER) {
            l1 = "GAME OVER";
            l2 = ru ? ("счёт " + score + "  OK - заново") : ("score " + score + "  OK - retry");
        } else if (state == ST_PAUSE) {
            l1 = ru ? "ПАУЗА" : "PAUSE";
            l2 = ru ? "OK - продолжить" : "OK - resume";
        } else {
            l1 = "SNAKE";
            l2 = ru ? "OK - играть, 2/4/6/8 - поворот" : "OK - play, 2/4/6/8 - turn";
        }
        int bw = Math.max(fT.stringWidth(l1), fS.stringWidth(l2)) + 24;
        int bh = fT.getHeight() + fS.getHeight() + 20;
        int bx = (w - bw) / 2;
        int byy = fieldY + (rows * CELL) / 2 - bh / 2;
        g.setColor(C_BG);
        g.fillRect(bx, byy, bw, bh);
        g.setColor(C_FRAME);
        g.drawRect(bx, byy, bw, bh);
        g.drawRect(bx + 1, byy + 1, bw - 2, bh - 2);
        g.setFont(fT);
        g.setColor(C_HEAD);
        g.drawString(l1, w / 2, byy + 8, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fS);
        g.setColor(C_DIM);
        g.drawString(l2, w / 2, byy + 8 + fT.getHeight() + 4, Graphics.TOP | Graphics.HCENTER);
    }

    // --- HIGH SCORE (RMS) ---

    private static final String HI_STORE = "snakehi";

    private int loadHi() {
        try {
            RecordStore rs = RecordStore.openRecordStore(HI_STORE, false);
            byte[] b = rs.getRecord(1);
            rs.closeRecordStore();
            return Integer.parseInt(new String(b, "UTF-8"));
        } catch (Exception e) {
            return 0;   // first launch has not happened yet
        }
    }

    private void saveHi(int v) {
        try {
            byte[] b = String.valueOf(v).getBytes("UTF-8");
            try {
                RecordStore rs = RecordStore.openRecordStore(HI_STORE, false);
                rs.setRecord(1, b, 0, b.length);
                rs.closeRecordStore();
            } catch (RecordStoreNotFoundException e) {
                RecordStore rs = RecordStore.openRecordStore(HI_STORE, true);
                rs.addRecord(b, 0, b.length);
                rs.closeRecordStore();
            }
        } catch (Exception e) {}
    }
}
