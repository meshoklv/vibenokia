package games.x2048;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Merge equal numbers: 2+2=4, 4+4=8... Goal: the 2048 tile. Arrow keys or 2/4/6/8. */
public class G2048 extends MIDlet implements CommandListener {

    private BoardCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new BoardCanvas();
            exitCmd = new Command("Выход", Command.EXIT, 2);
            newCmd = new Command("Заново", Command.SCREEN, 1);
            canvas.addCommand(newCmd);
            canvas.addCommand(exitCmd);
            canvas.setCommandListener(this);
        }
        Display.getDisplay(this).setCurrent(canvas);
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {
        if (canvas != null) canvas.save();
        notifyDestroyed();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) destroyApp(false);
        else if (c == newCmd) canvas.restart();
    }
}

class BoardCanvas extends Canvas {

    private static final int N = 4;
    private static final int BG = 0x101014, GRID = 0x2A2A32, INK = 0xE8E0D6, DIM = 0x8A8A94;

    private final int[] cellsArr = new int[N * N];
    private int score, hi;
    private boolean over = false, won = false;
    private final java.util.Random rnd = new java.util.Random();

    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fT = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    BoardCanvas() {
        setFullScreenMode(true);
        hi = loadHi();
        restart();
    }

    void restart() {
        for (int i = 0; i < cellsArr.length; i++) cellsArr[i] = 0;
        score = 0; over = false; won = false;
        addTile(); addTile();
        repaint();
    }

    private void addTile() {
        int free = 0;
        for (int i = 0; i < cellsArr.length; i++) if (cellsArr[i] == 0) free++;
        if (free == 0) return;
        int k = Math.abs(rnd.nextInt()) % free;
        for (int i = 0; i < cellsArr.length; i++) {
            if (cellsArr[i] != 0) continue;
            if (k-- == 0) { cellsArr[i] = (Math.abs(rnd.nextInt()) % 10 == 0) ? 4 : 2; return; }
        }
    }

    /** Slides the whole board. dir: 0 up, 1 right, 2 down, 3 left. */
    private boolean slide(int dir) {
        boolean moved = false;
        for (int line = 0; line < N; line++) {
            int[] row = new int[N];
            for (int i = 0; i < N; i++) row[i] = get(dir, line, i);
            int[] out = new int[N];
            int w = 0;
            int last = 0;
            for (int i = 0; i < N; i++) {
                int v = row[i];
                if (v == 0) continue;
                if (last == v) {                 // merge with the previous one
                    out[w - 1] = v * 2;
                    score += v * 2;
                    if (v * 2 == 2048) won = true;
                    last = 0;
                } else {
                    out[w++] = v;
                    last = v;
                }
            }
            for (int i = 0; i < N; i++) {
                if (out[i] != row[i]) moved = true;
                set(dir, line, i, out[i]);
            }
        }
        return moved;
    }

    /** Reads a cell relative to the move direction: line index, position i from the edge the tiles move towards. */
    private int get(int dir, int line, int i) {
        if (dir == 3) return cellsArr[line * N + i];            // left: left to right
        if (dir == 1) return cellsArr[line * N + (N - 1 - i)];  // right
        if (dir == 0) return cellsArr[i * N + line];            // up
        return cellsArr[(N - 1 - i) * N + line];                // down
    }

    private void set(int dir, int line, int i, int v) {
        if (dir == 3) cellsArr[line * N + i] = v;
        else if (dir == 1) cellsArr[line * N + (N - 1 - i)] = v;
        else if (dir == 0) cellsArr[i * N + line] = v;
        else cellsArr[(N - 1 - i) * N + line] = v;
    }

    private boolean canMove() {
        for (int i = 0; i < cellsArr.length; i++) {
            if (cellsArr[i] == 0) return true;
            int x = i % N, y = i / N;
            if (x + 1 < N && cellsArr[i] == cellsArr[i + 1]) return true;
            if (y + 1 < N && cellsArr[i] == cellsArr[i + N]) return true;
        }
        return false;
    }

    private void move(int dir) {
        if (over) return;
        if (!slide(dir)) return;
        addTile();
        if (score > hi) { hi = score; save(); }
        if (!canMove()) { over = true; save(); }
        repaint();
    }

    // --- high score ---

    private int loadHi() {
        try {
            RecordStore rs = RecordStore.openRecordStore("g2048hi", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    void save() {
        try {
            byte[] d = String.valueOf(hi).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("g2048hi", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    // --- input ---

    protected void keyPressed(int keyCode) {
        if (over && (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE)) { restart(); return; }
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM2 || ga == UP) move(0);
        else if (keyCode == KEY_NUM6 || ga == RIGHT) move(1);
        else if (keyCode == KEY_NUM8 || ga == DOWN) move(2);
        else if (keyCode == KEY_NUM4 || ga == LEFT) move(3);
    }

    // --- rendering ---

    private int tileColor(int v) {
        switch (v) {
            case 2:    return 0x3A3A44;
            case 4:    return 0x4A4A58;
            case 8:    return 0xE8A24B;
            case 16:   return 0xE8874B;
            case 32:   return 0xE8615F;
            case 64:   return 0xE0434B;
            case 128:  return 0xE8D84B;
            case 256:  return 0xD8C43B;
            case 512:  return 0x5FD16B;
            case 1024: return 0x4FD1D9;
            default:   return 0xB06BE0;
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        int headH = fS.getHeight() + 6;
        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Очки " + score, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        g.drawString("Рекорд " + hi, w - 4, 2, Graphics.TOP | Graphics.RIGHT);

        int side = (w < h - headH ? w : h - headH) - 8;
        int cell = side / N;
        int ox = (w - cell * N) / 2, oy = headH + (h - headH - cell * N) / 2;

        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                int v = cellsArr[y * N + x];
                int cx = ox + x * cell, cy = oy + y * cell;
                g.setColor(v == 0 ? GRID : tileColor(v));
                g.fillRect(cx + 2, cy + 2, cell - 4, cell - 4);
                if (v == 0) continue;
                String s = String.valueOf(v);
                g.setFont(fT);
                g.setColor(v <= 4 ? INK : 0x101014);
                g.drawString(s, cx + cell / 2, cy + (cell - fT.getHeight()) / 2,
                             Graphics.TOP | Graphics.HCENTER);
            }

        if (over) banner(g, w, h, "КОНЕЦ ИГРЫ", "5 — заново");
        else if (won) banner(g, w, h, "2048!", "можно играть дальше");
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
