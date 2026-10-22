package games.invaders;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** The aliens are advancing. 4/6 - move, 5 - fire. Do not let them reach the ground. */
public class Invaders extends MIDlet implements CommandListener {

    private InvCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new InvCanvas();
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

class InvCanvas extends Canvas implements Runnable {

    private static final int COLS = 7, ROWS = 4;
    private static final int BG = 0x101014, INK = 0xE8E0D6, DIM = 0x8A8A94;
    private static final int ALIEN = 0x5FD16B, ALIEN2 = 0x4FD1D9, SHIP = 0xE8E0D6, SHOT = 0xE8D84B;

    private final boolean[] alive = new boolean[COLS * ROWS];
    private int fleetX, fleetY, fleetDir = 1;
    private int shipX, shipY, shipW = 16;
    private int shotX = -1, shotY = -1;          // my shot
    private int bombX = -1, bombY = -1;          // alien shot
    private int score, hi, lives = 3, wave = 1;
    private boolean over = false, paused = false;
    private volatile boolean running = false;
    private int tick = 0;
    private final java.util.Random rnd = new java.util.Random();
    private int cellW = 18, cellH = 14;

    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    InvCanvas() {
        setFullScreenMode(true);
        hi = loadHi();
        restart();
    }

    void restart() {
        score = 0; lives = 3; wave = 1; over = false; paused = false;
        newWave();
        repaint();
    }

    private void newWave() {
        for (int i = 0; i < alive.length; i++) alive[i] = true;
        fleetX = 4;
        fleetY = fS.getHeight() + 10;
        fleetDir = 1;
        shotX = shotY = -1;
        bombX = bombY = -1;
        shipX = getWidth() / 2 - shipW / 2;
    }

    private int aliveCount() {
        int n = 0;
        for (int i = 0; i < alive.length; i++) if (alive[i]) n++;
        return n;
    }

    // --- step ---

    private synchronized void step() {
        if (over || paused) return;
        int w = getWidth(), h = getHeight();
        shipY = h - 16;
        if (shipX < 0) shipX = 0;
        if (shipX + shipW > w) shipX = w - shipW;

        tick++;
        // the fleet moves faster the fewer aliens remain
        int period = 12 - wave;
        int left = aliveCount();
        if (left < 8) period -= 3;
        if (period < 2) period = 2;
        if (tick % period == 0) {
            int minX = w, maxX = 0;
            for (int i = 0; i < alive.length; i++) {
                if (!alive[i]) continue;
                int ax = fleetX + (i % COLS) * cellW;
                if (ax < minX) minX = ax;
                if (ax + 12 > maxX) maxX = ax + 12;
            }
            if (maxX + fleetDir * 4 > w || minX + fleetDir * 4 < 0) {
                fleetDir = -fleetDir;
                fleetY += 8;
            } else {
                fleetX += fleetDir * 4;
            }
            // random bomb drop from the lowest live row
            if (bombY < 0 && left > 0 && Math.abs(rnd.nextInt()) % 3 == 0) {
                for (int t = 0; t < 12; t++) {
                    int c = Math.abs(rnd.nextInt()) % COLS;
                    for (int r = ROWS - 1; r >= 0; r--) {
                        if (!alive[r * COLS + c]) continue;
                        bombX = fleetX + c * cellW + 6;
                        bombY = fleetY + r * cellH + 10;
                        t = 99;
                        break;
                    }
                    if (t == 99) break;
                }
            }
        }

        if (shotY >= 0) {
            shotY -= 8;
            if (shotY < 0) shotX = shotY = -1;
            else checkHit();
        }
        if (bombY >= 0) {
            bombY += 4;
            if (bombY > h) { bombX = bombY = -1; }
            else if (bombY >= shipY && bombX >= shipX && bombX <= shipX + shipW) {
                bombX = bombY = -1;
                loseLife();
            }
        }

        // aliens reached the ground
        for (int i = 0; i < alive.length; i++) {
            if (!alive[i]) continue;
            if (fleetY + (i / COLS) * cellH + 10 >= shipY) { over = true; saveHi(); return; }
        }
        if (aliveCount() == 0) { wave++; score += 100; newWave(); }
    }

    private void checkHit() {
        for (int i = 0; i < alive.length; i++) {
            if (!alive[i]) continue;
            int ax = fleetX + (i % COLS) * cellW, ay = fleetY + (i / COLS) * cellH;
            if (shotX >= ax && shotX <= ax + 12 && shotY >= ay && shotY <= ay + 10) {
                alive[i] = false;
                shotX = shotY = -1;
                score += 10 * (ROWS - i / COLS);
                if (score > hi) { hi = score; }
                return;
            }
        }
    }

    private void loseLife() {
        lives--;
        if (lives <= 0) { over = true; saveHi(); }
    }

    // --- game thread ---

    public void run() {
        while (running) {
            try { Thread.sleep(45); } catch (Exception e) { break; }
            step();
            repaint();
        }
    }

    void resume() { if (!running) { running = true; new Thread(this).start(); } }
    void pause() { paused = true; }
    void shutdown() { running = false; saveHi(); }
    protected void hideNotify() { paused = true; }

    private int loadHi() {
        try {
            RecordStore rs = RecordStore.openRecordStore("invadershi", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    private void saveHi() {
        try {
            byte[] d = String.valueOf(hi).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("invadershi", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    protected void keyPressed(int keyCode) {
        if (over) { if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) restart(); return; }
        if (keyCode == KEY_STAR || keyCode == KEY_POUND) { paused = !paused; repaint(); return; }
        paused = false;
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM4 || ga == LEFT) shipX -= 10;
        else if (keyCode == KEY_NUM6 || ga == RIGHT) shipX += 10;
        else if (keyCode == KEY_NUM5 || ga == FIRE || ga == UP) {
            if (shotY < 0) { shotX = shipX + shipW / 2; shotY = shipY - 4; }
        }
        repaint();
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Очки " + score, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        g.drawString("Волна " + wave + "  Жизни " + lives + "  Рек. " + hi, w - 4, 2,
                     Graphics.TOP | Graphics.RIGHT);

        for (int i = 0; i < alive.length; i++) {
            if (!alive[i]) continue;
            int ax = fleetX + (i % COLS) * cellW, ay = fleetY + (i / COLS) * cellH;
            g.setColor((i / COLS) % 2 == 0 ? ALIEN : ALIEN2);
            g.fillRect(ax + 2, ay + 2, 8, 5);
            g.fillRect(ax, ay + 4, 12, 3);
            g.fillRect(ax + 1, ay, 2, 3);
            g.fillRect(ax + 9, ay, 2, 3);
        }

        g.setColor(SHIP);
        g.fillRect(shipX, shipY, shipW, 4);
        g.fillRect(shipX + shipW / 2 - 2, shipY - 4, 4, 4);

        if (shotY >= 0) { g.setColor(SHOT); g.fillRect(shotX, shotY, 2, 6); }
        if (bombY >= 0) { g.setColor(0xE8615F); g.fillRect(bombX, bombY, 2, 6); }

        if (over) banner(g, w, h, "КОНЕЦ ИГРЫ", "5 — заново");
        else if (paused) banner(g, w, h, "ПАУЗА", "* — продолжить");
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
