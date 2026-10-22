package games.pairs;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Find pairs of matching cards in as few moves as possible. Arrows - cursor, 5 - flip. */
public class Pairs extends MIDlet implements CommandListener {

    private PairsCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new PairsCanvas();
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

class PairsCanvas extends Canvas implements Runnable {

    private static final int COLS = 4, ROWS = 5;          // 20 cards = 10 pairs
    private static final int BG = 0x101014, INK = 0xE8E0D6, DIM = 0x8A8A94, BACK = 0x3A3A44;
    private static final int[] COLORS = {
        0xE8615F, 0x5FD16B, 0x4A7BEA, 0xE8D84B, 0xB06BE0,
        0x4FD1D9, 0xE8A24B, 0xF08CAA, 0xE8E0D6, 0x7A8AA0
    };

    private final int[] card = new int[COLS * ROWS];
    private final boolean[] shown = new boolean[COLS * ROWS];
    private final boolean[] found = new boolean[COLS * ROWS];
    private int first = -1, second = -1;
    private int cx = 0, cy = 0, moves = 0, best;
    private boolean won = false;
    private volatile boolean waiting = false;    // pair is open, wait and then close it
    private final java.util.Random rnd = new java.util.Random();

    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    PairsCanvas() {
        setFullScreenMode(true);
        best = loadBest();
        restart();
    }

    void restart() {
        int n = COLS * ROWS;
        for (int i = 0; i < n; i++) { card[i] = i / 2; shown[i] = false; found[i] = false; }
        for (int i = n - 1; i > 0; i--) {          // Fisher-Yates shuffle
            int j = Math.abs(rnd.nextInt()) % (i + 1);
            int t = card[i]; card[i] = card[j]; card[j] = t;
        }
        first = second = -1; moves = 0; won = false; waiting = false;
        cx = cy = 0;
        repaint();
    }

    private void openCard(int idx) {
        if (waiting || found[idx] || shown[idx] || won) return;
        shown[idx] = true;
        if (first < 0) { first = idx; repaint(); return; }
        second = idx;
        moves++;
        repaint();
        if (card[first] == card[second]) {
            found[first] = true; found[second] = true;
            first = second = -1;
            checkWin();
        } else {
            waiting = true;
            new Thread(this).start();              // keep them open for a moment, then close
        }
    }

    public void run() {
        try { Thread.sleep(700); } catch (Exception e) {}
        if (first >= 0) shown[first] = false;
        if (second >= 0) shown[second] = false;
        first = second = -1;
        waiting = false;
        repaint();
    }

    private void checkWin() {
        for (int i = 0; i < found.length; i++) if (!found[i]) return;
        won = true;
        if (best == 0 || moves < best) { best = moves; saveBest(); }
    }

    private int loadBest() {
        try {
            RecordStore rs = RecordStore.openRecordStore("pairsbest", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    private void saveBest() {
        try {
            byte[] d = String.valueOf(best).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("pairsbest", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    protected void keyPressed(int keyCode) {
        if (won) { if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) restart(); return; }
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM4 || ga == LEFT) cx = (cx + COLS - 1) % COLS;
        else if (keyCode == KEY_NUM6 || ga == RIGHT) cx = (cx + 1) % COLS;
        else if (keyCode == KEY_NUM2 || ga == UP) cy = (cy + ROWS - 1) % ROWS;
        else if (keyCode == KEY_NUM8 || ga == DOWN) cy = (cy + 1) % ROWS;
        else if (keyCode == KEY_NUM5 || ga == FIRE) openCard(cy * COLS + cx);
        repaint();
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        int headH = fS.getHeight() + 6;
        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Ходы " + moves, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        g.drawString(best > 0 ? ("Рекорд " + best) : "", w - 4, 2, Graphics.TOP | Graphics.RIGHT);

        int cw = (w - 10) / COLS, chh = (h - headH - 10) / ROWS;
        int ox = (w - cw * COLS) / 2, oy = headH + (h - headH - chh * ROWS) / 2;

        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++) {
                int i = y * COLS + x;
                int px = ox + x * cw, py = oy + y * chh;
                boolean face = shown[i] || found[i];
                g.setColor(face ? COLORS[card[i]] : BACK);
                g.fillRect(px + 2, py + 2, cw - 5, chh - 5);
                if (found[i]) {
                    g.setColor(0x101014);
                    g.drawRect(px + 4, py + 4, cw - 9, chh - 9);
                }
                if (x == cx && y == cy && !won) {
                    g.setColor(INK);
                    g.drawRect(px + 1, py + 1, cw - 3, chh - 3);
                }
            }

        if (won) banner(g, w, h, "ВСЕ ПАРЫ НАЙДЕНЫ", moves + " ходов · 5 — заново");
    }

    private void banner(Graphics g, int w, int h, String title, String hint) {
        int bh = fB.getHeight() + fS.getHeight() + 14;
        int by = (h - bh) / 2;
        g.setColor(0x000000);
        g.fillRect(0, by, w, bh);
        g.setColor(0x2A2A32);
        g.drawRect(0, by, w - 1, bh - 1);
        g.setFont(fB);
        g.setColor(INK);
        g.drawString(title, w / 2, by + 5, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fS);
        g.setColor(DIM);
        g.drawString(hint, w / 2, by + 7 + fB.getHeight(), Graphics.TOP | Graphics.HCENTER);
    }
}
