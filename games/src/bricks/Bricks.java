package games.bricks;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Bounce the ball off the paddle and break the bricks. 4/6 - paddle, 5 - launch the ball. */
public class Bricks extends MIDlet implements CommandListener {

    private BricksCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new BricksCanvas();
            exitCmd = new Command("Выход", Command.EXIT, 2);
            newCmd = new Command("Заново", Command.SCREEN, 1);
            canvas.addCommand(newCmd);
            canvas.addCommand(exitCmd);
            canvas.setCommandListener(this);
        }
        Display.getDisplay(this).setCurrent(canvas);
        canvas.resume();
    }

    public void pauseApp() { if (canvas != null) canvas.pause(); }

    public void destroyApp(boolean unconditional) {
        if (canvas != null) canvas.shutdown();
        notifyDestroyed();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) destroyApp(false);
        else if (c == newCmd) canvas.restart();
    }
}

class BricksCanvas extends Canvas implements Runnable {

    private static final int COLS = 8, ROWS = 5;
    private static final int BG = 0x101014, INK = 0xE8E0D6, DIM = 0x8A8A94;
    private static final int[] ROWC = {0xE8615F, 0xE8A24B, 0xE8D84B, 0x5FD16B, 0x4FD1D9};

    private final boolean[] brick = new boolean[COLS * ROWS];
    private int bw, bh, top;                 // brick geometry
    private int padX, padW, padY, padH;
    private int bx, by, vx, vy, ballR;       // ball
    private boolean stuck = true;            // ball rests on the paddle
    private int score, hi, lives = 3, level = 1;
    private boolean over = false, won = false, paused = false;
    private volatile boolean running = false;
    private Thread loop;

    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    BricksCanvas() {
        setFullScreenMode(true);
        hi = loadHi();
    }

    private void layout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0) return;
        top = fS.getHeight() + 6;
        bw = (w - 8) / COLS;
        bh = 10;
        padW = w / 5; padH = 5;
        padY = h - 14;
        if (padX == 0) padX = (w - padW) / 2;
        ballR = 3;
    }

    void restart() {
        layout();
        score = 0; lives = 3; level = 1;
        over = false; won = false; paused = false;
        fillBricks();
        resetBall();
        repaint();
    }

    private void fillBricks() {
        for (int i = 0; i < brick.length; i++) brick[i] = true;
    }

    private void resetBall() {
        layout();
        padX = (getWidth() - padW) / 2;
        bx = padX + padW / 2;
        by = padY - ballR - 1;
        vx = 2; vy = -2;
        stuck = true;
    }

    // --- game step ---

    private synchronized void step() {
        if (over || won || paused) return;
        if (stuck) { bx = padX + padW / 2; by = padY - ballR - 1; return; }
        int w = getWidth();
        bx += vx; by += vy;
        if (bx - ballR < 0) { bx = ballR; vx = -vx; }
        if (bx + ballR > w) { bx = w - ballR; vx = -vx; }
        if (by - ballR < top) { by = top + ballR; vy = -vy; }

        // paddle: bounce angle depends on the hit point
        if (vy > 0 && by + ballR >= padY && by + ballR <= padY + padH + 4
            && bx >= padX - 2 && bx <= padX + padW + 2) {
            by = padY - ballR;
            vy = -vy;
            int rel = bx - (padX + padW / 2);
            vx = rel / (padW / 6 > 0 ? padW / 6 : 1);
            if (vx > 4) vx = 4;
            if (vx < -4) vx = -4;
            if (vx == 0) vx = (rel < 0) ? -1 : 1;
        }

        hitBrick();

        if (by - ballR > getHeight()) {
            lives--;
            if (lives <= 0) { over = true; saveHi(); }
            else resetBall();
        }
    }

    private void hitBrick() {
        if (by - ballR > top + ROWS * bh) return;
        int col = (bx - 4) / bw;
        int row = (by - top) / bh;
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return;
        int i = row * COLS + col;
        if (!brick[i]) return;
        brick[i] = false;
        vy = -vy;
        score += 10 * level;
        if (score > hi) { hi = score; }
        for (int k = 0; k < brick.length; k++) if (brick[k]) return;
        // all bricks cleared - next level
        level++;
        fillBricks();
        resetBall();
        saveHi();
    }

    // --- game thread ---

    public void run() {
        while (running) {
            try { Thread.sleep(28); } catch (Exception e) { break; }
            step();
            repaint();
        }
    }

    void resume() { if (!running) { running = true; loop = new Thread(this); loop.start(); } }
    void pause() { paused = true; }
    void shutdown() { running = false; saveHi(); }
    protected void hideNotify() { paused = true; }
    protected void showNotify() { layout(); }

    // --- high score ---

    private int loadHi() {
        try {
            RecordStore rs = RecordStore.openRecordStore("brickshi", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    private void saveHi() {
        try {
            byte[] d = String.valueOf(hi).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("brickshi", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    // --- input ---

    protected void keyPressed(int keyCode) {
        if (over) { if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) restart(); return; }
        if (keyCode == KEY_STAR || keyCode == KEY_POUND) { paused = !paused; repaint(); return; }
        int ga = getGameAction(keyCode);
        int stepX = padW / 3;
        if (keyCode == KEY_NUM4 || ga == LEFT) padX -= stepX;
        else if (keyCode == KEY_NUM6 || ga == RIGHT) padX += stepX;
        else if (keyCode == KEY_NUM5 || ga == FIRE) { stuck = false; paused = false; }
        clampPad();
        repaint();
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    private void clampPad() {
        if (padX < 0) padX = 0;
        if (padX + padW > getWidth()) padX = getWidth() - padW;
    }

    // --- rendering ---

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (bw == 0) { layout(); restart(); }
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Очки " + score, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        g.drawString("Ур." + level + "  Жизни " + lives + "  Рек. " + hi, w - 4, 2,
                     Graphics.TOP | Graphics.RIGHT);

        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++) {
                if (!brick[r * COLS + c]) continue;
                g.setColor(ROWC[r % ROWC.length]);
                g.fillRect(4 + c * bw, top + r * bh, bw - 2, bh - 2);
            }

        g.setColor(INK);
        g.fillRect(padX, padY, padW, padH);
        g.setColor(0xE8D84B);
        g.fillArc(bx - ballR, by - ballR, ballR * 2, ballR * 2, 0, 360);

        if (stuck && !over) {
            g.setColor(DIM);
            g.drawString("5 — пуск", w / 2, padY - 20, Graphics.TOP | Graphics.HCENTER);
        }
        if (over) banner(g, w, h, "КОНЕЦ ИГРЫ", "5 — заново");
        else if (paused) banner(g, w, h, "ПАУЗА", "* — продолжить");
    }

    private void banner(Graphics g, int w, int h, String title, String hint) {
        int bh2 = fB.getHeight() + fS.getHeight() + 14;
        int by2 = (h - bh2) / 2;
        g.setColor(0x000000);
        g.fillRect(0, by2, w, bh2);
        g.setColor(0x2A2A32);
        g.drawRect(0, by2, w - 1, bh2 - 1);
        g.setFont(fB);
        g.setColor(INK);
        g.drawString(title, w / 2, by2 + 5, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fS);
        g.setColor(DIM);
        g.drawString(hint, w / 2, by2 + 7 + fB.getHeight(), Graphics.TOP | Graphics.HCENTER);
    }
}
