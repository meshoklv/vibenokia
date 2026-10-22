package games.blocks;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Classic falling blocks. Controls: 4/6 - move sideways, 2 - rotate, 8 - down, 5 - drop. */
public class Blocks extends MIDlet implements CommandListener {

    private BlocksCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new BlocksCanvas();
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

class BlocksCanvas extends Canvas implements Runnable {

    private static final int COLS = 10, ROWS = 18;

    // 7 pieces, 4 rotations each, every one a 4x4 mask (most significant bit = top-left corner)
    private static final int[][] SHAPES = {
        {0x0F00, 0x2222, 0x00F0, 0x4444},   // I
        {0x8E00, 0x6440, 0x0E20, 0x44C0},   // J
        {0x2E00, 0x4460, 0x0E80, 0xC440},   // L
        {0x6600, 0x6600, 0x6600, 0x6600},   // O
        {0x6C00, 0x4620, 0x06C0, 0x8C40},   // S
        {0x4E00, 0x4640, 0x0E40, 0x4C40},   // T
        {0xC600, 0x2640, 0x0C60, 0x4C80}    // Z
    };
    private static final int[] COLORS = {
        0x4FD1D9, 0x4A7BEA, 0xE8A24B, 0xE8D84B, 0x5FD16B, 0xB06BE0, 0xE8615F
    };

    private static final int BG = 0x101014, GRID = 0x2A2A32, INK = 0xE8E0D6, DIM = 0x8A8A94;

    private final int[] field = new int[COLS * ROWS];   // 0 = empty, otherwise colour+1
    private int piece, rot, px, py, nextPiece;
    private int score, lines, level, hi;
    private boolean over = false, paused = false;
    private volatile boolean running = false;
    private Thread loop;
    private final java.util.Random rnd = new java.util.Random();

    private int cell, offX, offY, headH;
    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    BlocksCanvas() {
        setFullScreenMode(true);
        hi = loadHi();
        restart();
    }

    // --- game round ---

    void restart() {
        for (int i = 0; i < field.length; i++) field[i] = 0;
        score = 0; lines = 0; level = 1;
        over = false; paused = false;
        nextPiece = Math.abs(rnd.nextInt()) % SHAPES.length;
        spawn();
        repaint();
    }

    private void spawn() {
        piece = nextPiece;
        nextPiece = Math.abs(rnd.nextInt()) % SHAPES.length;
        rot = 0; px = 3; py = -1;
        if (hits(piece, rot, px, py + 1)) { over = true; saveHi(); }
    }

    /** Whether the piece overlaps the walls or blocks already placed. */
    private boolean hits(int p, int r, int x, int y) {
        int mask = SHAPES[p][r];
        for (int i = 0; i < 16; i++) {
            if ((mask & (0x8000 >> i)) == 0) continue;
            int cx = x + (i % 4), cy = y + (i / 4);
            if (cx < 0 || cx >= COLS || cy >= ROWS) return true;
            if (cy >= 0 && field[cy * COLS + cx] != 0) return true;
        }
        return false;
    }

    private void lock() {
        int mask = SHAPES[piece][rot];
        for (int i = 0; i < 16; i++) {
            if ((mask & (0x8000 >> i)) == 0) continue;
            int cx = px + (i % 4), cy = py + (i / 4);
            if (cy < 0) { over = true; saveHi(); return; }
            field[cy * COLS + cx] = piece + 1;
        }
        clearLines();
        spawn();
    }

    private void clearLines() {
        int got = 0;
        for (int y = ROWS - 1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < COLS; x++) if (field[y * COLS + x] == 0) { full = false; break; }
            if (!full) continue;
            got++;
            for (int yy = y; yy > 0; yy--)
                for (int x = 0; x < COLS; x++) field[yy * COLS + x] = field[(yy - 1) * COLS + x];
            for (int x = 0; x < COLS; x++) field[x] = 0;
            y++;   // row shifted down - check the same row again
        }
        if (got == 0) return;
        int[] pay = {0, 100, 300, 500, 800};
        score += pay[got > 4 ? 4 : got] * level;
        lines += got;
        level = 1 + lines / 10;
        if (score > hi) { hi = score; saveHi(); }
    }

    private synchronized void step() {
        if (over || paused) return;
        if (hits(piece, rot, px, py + 1)) lock(); else py++;
    }

    // --- game thread ---

    public void run() {
        while (running) {
            int delay = 700 - (level - 1) * 55;
            if (delay < 110) delay = 110;
            try { Thread.sleep(delay); } catch (Exception e) { break; }
            step();
            repaint();
        }
    }

    void resume() {
        if (loop == null || !running) { running = true; loop = new Thread(this); loop.start(); }
    }

    void pause() { paused = true; }

    void shutdown() { running = false; saveHi(); }

    protected void hideNotify() { paused = true; }

    // --- high score ---

    private int loadHi() {
        try {
            RecordStore rs = RecordStore.openRecordStore("blockshi", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    private void saveHi() {
        try {
            byte[] d = String.valueOf(hi).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("blockshi", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    // --- input ---

    protected void keyPressed(int keyCode) {
        if (over) { if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) restart(); return; }
        if (keyCode == KEY_STAR || keyCode == KEY_POUND) { paused = !paused; repaint(); return; }
        if (paused) { paused = false; repaint(); return; }
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM4 || ga == LEFT)       { if (!hits(piece, rot, px - 1, py)) px--; }
        else if (keyCode == KEY_NUM6 || ga == RIGHT) { if (!hits(piece, rot, px + 1, py)) px++; }
        else if (keyCode == KEY_NUM2 || ga == UP)    { rotate(); }
        else if (keyCode == KEY_NUM8 || ga == DOWN)  { if (!hits(piece, rot, px, py + 1)) { py++; score++; } }
        else if (keyCode == KEY_NUM5 || ga == FIRE)  { drop(); }
        repaint();
    }

    private void rotate() {
        int r = (rot + 1) % 4;
        if (!hits(piece, r, px, py)) { rot = r; return; }
        if (!hits(piece, r, px - 1, py)) { rot = r; px--; return; }   // wall kick
        if (!hits(piece, r, px + 1, py)) { rot = r; px++; }
    }

    private synchronized void drop() {
        while (!hits(piece, rot, px, py + 1)) { py++; score += 2; }
        lock();
    }

    // --- rendering ---

    private void layout(int w, int h) {
        headH = fS.getHeight() * 2 + 6;
        int cw = (w - 8) / COLS;
        int ch = (h - headH - 8) / ROWS;
        cell = cw < ch ? cw : ch;
        offX = (w - cell * COLS) / 2;
        offY = headH + (h - headH - cell * ROWS) / 2;
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (cell == 0) layout(w, h);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Очки " + score, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        g.drawString("Рекорд " + hi, w - 4, 2, Graphics.TOP | Graphics.RIGHT);
        g.drawString("Уровень " + level + "   Линии " + lines, 4, 2 + fS.getHeight(),
                     Graphics.TOP | Graphics.LEFT);
        drawNext(g, w);

        g.setColor(GRID);
        g.drawRect(offX - 1, offY - 1, cell * COLS + 1, cell * ROWS + 1);

        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++) {
                int v = field[y * COLS + x];
                if (v != 0) drawCell(g, offX + x * cell, offY + y * cell, COLORS[v - 1]);
            }

        if (!over) {
            int mask = SHAPES[piece][rot];
            for (int i = 0; i < 16; i++) {
                if ((mask & (0x8000 >> i)) == 0) continue;
                int cx = px + (i % 4), cy = py + (i / 4);
                if (cy < 0) continue;
                drawCell(g, offX + cx * cell, offY + cy * cell, COLORS[piece]);
            }
        }

        if (over) banner(g, w, h, "КОНЕЦ ИГРЫ", "5 — заново");
        else if (paused) banner(g, w, h, "ПАУЗА", "любая клавиша");
    }

    private void drawNext(Graphics g, int w) {
        int s = 4, bx = w - 4 - s * 4, by = 2 + fS.getHeight();
        int mask = SHAPES[nextPiece][0];
        g.setColor(COLORS[nextPiece]);
        for (int i = 0; i < 16; i++) {
            if ((mask & (0x8000 >> i)) == 0) continue;
            g.fillRect(bx + (i % 4) * s, by + (i / 4) * s, s - 1, s - 1);
        }
    }

    private void drawCell(Graphics g, int x, int y, int color) {
        g.setColor(color);
        g.fillRect(x, y, cell - 1, cell - 1);
        g.setColor(0x000000);
        g.drawRect(x, y, cell - 1, cell - 1);
    }

    private void banner(Graphics g, int w, int h, String title, String hint) {
        int bh = fB.getHeight() + fS.getHeight() + 14;
        int by = (h - bh) / 2;
        g.setColor(0x000000);
        g.fillRect(0, by, w, bh);
        g.setColor(GRID);
        g.drawRect(0, by, w - 1, bh - 1);
        g.setFont(fB);
        g.setColor(INK);
        g.drawString(title, w / 2, by + 5, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fS);
        g.setColor(DIM);
        g.drawString(hint, w / 2, by + 7 + fB.getHeight(), Graphics.TOP | Graphics.HCENTER);
    }
}
