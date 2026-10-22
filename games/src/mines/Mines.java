package games.mines;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Minesweeper 9x9, 10 mines. Arrows - cursor, 5 - reveal, # - flag. The first move is always safe. */
public class Mines extends MIDlet implements CommandListener {

    private MinesCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new MinesCanvas();
            exitCmd = new Command("Выход", Command.EXIT, 2);
            newCmd = new Command("Заново", Command.SCREEN, 1);
            canvas.addCommand(newCmd);
            canvas.addCommand(exitCmd);
            canvas.setCommandListener(this);
        }
        Display.getDisplay(this).setCurrent(canvas);
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) { notifyDestroyed(); }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) destroyApp(false);
        else if (c == newCmd) canvas.restart();
    }
}

class MinesCanvas extends Canvas {

    private static final int N = 9, MINES = 10;
    private static final int BG = 0x101014, GRID = 0x2A2A32, INK = 0xE8E0D6, DIM = 0x8A8A94;
    private static final int[] NUMC = {
        0x8A8A94, 0x4A7BEA, 0x5FD16B, 0xE8615F, 0xB06BE0, 0xE8A24B, 0x4FD1D9, 0xE8E0D6, 0x8A8A94
    };

    private final boolean[] mine = new boolean[N * N];
    private final boolean[] open = new boolean[N * N];
    private final boolean[] flag = new boolean[N * N];
    private int cx = N / 2, cy = N / 2;
    private boolean started = false, over = false, won = false;
    private int best;
    private long t0;
    private int seconds;
    private final java.util.Random rnd = new java.util.Random();

    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fC = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    MinesCanvas() {
        setFullScreenMode(true);
        best = loadBest();
        restart();
    }

    void restart() {
        for (int i = 0; i < N * N; i++) { mine[i] = false; open[i] = false; flag[i] = false; }
        started = false; over = false; won = false; seconds = 0;
        cx = N / 2; cy = N / 2;
        repaint();
    }

    /** Mines are placed AFTER the first move, so the area around it is always clear. */
    private void placeMines(int safeIdx) {
        int put = 0;
        while (put < MINES) {
            int i = Math.abs(rnd.nextInt()) % (N * N);
            if (mine[i] || i == safeIdx) continue;
            int sx = safeIdx % N, sy = safeIdx / N, x = i % N, y = i / N;
            if (Math.abs(sx - x) <= 1 && Math.abs(sy - y) <= 1) continue;
            mine[i] = true;
            put++;
        }
        started = true;
        t0 = System.currentTimeMillis();
    }

    private int around(int x, int y) {
        int n = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
                if (mine[ny * N + nx]) n++;
            }
        return n;
    }

    private void openCell(int x, int y) {
        int idx = y * N + x;
        if (open[idx] || flag[idx]) return;
        if (!started) placeMines(idx);
        if (mine[idx]) { over = true; revealAll(); return; }
        // flood fill over empty cells without recursion - array-based stack
        // with spare capacity: one cell may be pushed by several neighbours
        int[] stack = new int[N * N * 8];
        int sp = 0;
        stack[sp++] = idx;
        while (sp > 0) {
            int cur = stack[--sp];
            if (open[cur] || flag[cur]) continue;
            open[cur] = true;
            int qx = cur % N, qy = cur / N;
            if (around(qx, qy) != 0) continue;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = qx + dx, ny = qy + dy;
                    if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
                    int ni = ny * N + nx;
                    if (!open[ni] && !mine[ni] && sp < stack.length) stack[sp++] = ni;
                }
        }
        checkWin();
    }

    private void revealAll() {
        for (int i = 0; i < N * N; i++) if (mine[i]) open[i] = true;
    }

    private void checkWin() {
        for (int i = 0; i < N * N; i++) if (!mine[i] && !open[i]) return;
        won = true;
        seconds = elapsed();
        if (best == 0 || seconds < best) { best = seconds; saveBest(); }
    }

    private int elapsed() {
        if (!started) return 0;
        if (over || won) return seconds > 0 ? seconds : (int) ((System.currentTimeMillis() - t0) / 1000);
        return (int) ((System.currentTimeMillis() - t0) / 1000);
    }

    private int loadBest() {
        try {
            RecordStore rs = RecordStore.openRecordStore("mineshi", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    private void saveBest() {
        try {
            byte[] d = String.valueOf(best).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("mineshi", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    protected void keyPressed(int keyCode) {
        if (over || won) {
            if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) restart();
            repaint();
            return;
        }
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM4 || ga == LEFT) cx = (cx + N - 1) % N;
        else if (keyCode == KEY_NUM6 || ga == RIGHT) cx = (cx + 1) % N;
        else if (keyCode == KEY_NUM2 || ga == UP) cy = (cy + N - 1) % N;
        else if (keyCode == KEY_NUM8 || ga == DOWN) cy = (cy + 1) % N;
        else if (keyCode == KEY_NUM5 || ga == FIRE) openCell(cx, cy);
        else if (keyCode == KEY_POUND || keyCode == KEY_STAR) {
            int i = cy * N + cx;
            if (!open[i]) flag[i] = !flag[i];
        }
        repaint();
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        int flags = 0;
        for (int i = 0; i < N * N; i++) if (flag[i]) flags++;

        int headH = fS.getHeight() + 6;
        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Мины " + (MINES - flags), 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        String b = best > 0 ? ("  Рек. " + best + "с") : "";
        g.drawString(elapsed() + "с" + b, w - 4, 2, Graphics.TOP | Graphics.RIGHT);

        int side = (w < h - headH ? w : h - headH) - 8;
        int cell = side / N;
        int ox = (w - cell * N) / 2, oy = headH + (h - headH - cell * N) / 2;

        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                int i = y * N + x;
                int px = ox + x * cell, py = oy + y * cell;
                if (open[i]) {
                    g.setColor(0x1A1A20);
                    g.fillRect(px, py, cell - 1, cell - 1);
                    if (mine[i]) {
                        g.setColor(0xE8615F);
                        g.fillArc(px + 3, py + 3, cell - 7, cell - 7, 0, 360);
                    } else {
                        int n = around(x, y);
                        if (n > 0) {
                            g.setFont(fC);
                            g.setColor(NUMC[n]);
                            g.drawString(String.valueOf(n), px + cell / 2,
                                         py + (cell - fC.getHeight()) / 2 + 1,
                                         Graphics.TOP | Graphics.HCENTER);
                        }
                    }
                } else {
                    g.setColor(0x3A3A44);
                    g.fillRect(px, py, cell - 1, cell - 1);
                    if (flag[i]) {
                        g.setColor(0xE8D84B);
                        g.fillRect(px + cell / 3, py + 3, 2, cell - 7);
                        g.fillTriangle(px + cell / 3 + 2, py + 3,
                                       px + cell - 4, py + cell / 3,
                                       px + cell / 3 + 2, py + cell / 2);
                    }
                }
                if (x == cx && y == cy && !over && !won) {
                    g.setColor(0xE8E0D6);
                    g.drawRect(px, py, cell - 1, cell - 1);
                }
            }

        g.setColor(GRID);
        g.drawRect(ox - 1, oy - 1, cell * N + 1, cell * N + 1);

        if (over) banner(g, w, h, "БАБАХ", "5 — заново");
        else if (won) banner(g, w, h, "ПОЛЕ ЧИСТО", seconds + " сек · 5 — заново");
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
