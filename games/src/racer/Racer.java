package games.racer;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Night city from above: race down the road, dodge cars, collect money. 4/6 - steer, 8 - brake. */
public class Racer extends MIDlet implements CommandListener {

    private RacerCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new RacerCanvas();
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

class RacerCanvas extends Canvas implements Runnable {

    private static final int LANES = 4, MAXOBJ = 10;
    private static final int BG = 0x101014, ROAD = 0x22222A, LINE = 0x8A8A94;
    private static final int INK = 0xE8E0D6, DIM = 0x8A8A94;
    private static final int[] CARC = {0xE8615F, 0x4A7BEA, 0x5FD16B, 0xB06BE0, 0xE8A24B};

    private int lane = 1, carY;
    private int roadX, roadW, laneW;
    private int scroll = 0, speed = 6;
    private int score, cash, hi;
    private boolean over = false, paused = false;
    private volatile boolean running = false;

    // obstacles and money: type 0 - car, 1 - money
    private final int[] oLane = new int[MAXOBJ];
    private final int[] oY = new int[MAXOBJ];
    private final int[] oType = new int[MAXOBJ];
    private final int[] oColor = new int[MAXOBJ];
    private final boolean[] oOn = new boolean[MAXOBJ];

    private final java.util.Random rnd = new java.util.Random();
    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    RacerCanvas() {
        setFullScreenMode(true);
        hi = loadHi();
        restart();
    }

    private void layout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0) return;
        roadW = w - 20;
        roadX = 10;
        laneW = roadW / LANES;
        carY = h - 46;
    }

    void restart() {
        layout();
        lane = 1; scroll = 0; speed = 6; score = 0; cash = 0;
        over = false; paused = false;
        for (int i = 0; i < MAXOBJ; i++) oOn[i] = false;
        repaint();
    }

    private void spawn() {
        for (int i = 0; i < MAXOBJ; i++) {
            if (oOn[i]) continue;
            oOn[i] = true;
            oLane[i] = Math.abs(rnd.nextInt()) % LANES;
            oY[i] = -40 - Math.abs(rnd.nextInt()) % 60;
            oType[i] = (Math.abs(rnd.nextInt()) % 5 == 0) ? 1 : 0;
            oColor[i] = CARC[Math.abs(rnd.nextInt()) % CARC.length];
            return;
        }
    }

    private synchronized void step() {
        if (over || paused) return;
        int h = getHeight();
        scroll = (scroll + speed) % 40;
        score += speed / 3 + 1;
        if (score / 400 + 6 > speed && speed < 18) speed++;   // speed up along the way

        if (Math.abs(rnd.nextInt()) % Math.max(2, 14 - speed) == 0) spawn();

        for (int i = 0; i < MAXOBJ; i++) {
            if (!oOn[i]) continue;
            oY[i] += speed + (oType[i] == 0 ? -2 : 0);        // oncoming cars are slightly slower
            if (oY[i] > h) { oOn[i] = false; continue; }
            if (oLane[i] != lane) continue;
            if (oY[i] + 30 < carY || oY[i] > carY + 30) continue;
            if (oType[i] == 1) { oOn[i] = false; cash += 50; score += 50; }
            else { over = true; if (score > hi) { hi = score; } saveHi(); }
        }
    }

    public void run() {
        while (running) {
            try { Thread.sleep(40); } catch (Exception e) { break; }
            step();
            repaint();
        }
    }

    void resume() { if (!running) { running = true; new Thread(this).start(); } }
    void pause() { paused = true; }
    void shutdown() { running = false; saveHi(); }
    protected void hideNotify() { paused = true; }
    protected void showNotify() { layout(); }

    private int loadHi() {
        try {
            RecordStore rs = RecordStore.openRecordStore("racerhi", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    private void saveHi() {
        try {
            byte[] d = String.valueOf(hi).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("racerhi", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    protected void keyPressed(int keyCode) {
        if (over) { if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) restart(); return; }
        if (keyCode == KEY_STAR) { paused = !paused; repaint(); return; }
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM4 || ga == LEFT) { if (lane > 0) lane--; }
        else if (keyCode == KEY_NUM6 || ga == RIGHT) { if (lane < LANES - 1) lane++; }
        else if (keyCode == KEY_NUM2 || ga == UP) { if (speed < 18) speed++; }
        else if (keyCode == KEY_NUM8 || ga == DOWN) { if (speed > 4) speed--; }
        repaint();
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (roadW == 0) layout();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        g.setColor(ROAD);
        g.fillRect(roadX, 0, roadW, h);
        g.setColor(LINE);
        for (int l = 1; l < LANES; l++) {
            int x = roadX + l * laneW;
            for (int y = -40 + scroll; y < h; y += 40) g.fillRect(x - 1, y, 2, 20);
        }
        g.setColor(0x3C3C46);
        g.fillRect(roadX - 3, 0, 3, h);
        g.fillRect(roadX + roadW, 0, 3, h);

        for (int i = 0; i < MAXOBJ; i++) {
            if (!oOn[i]) continue;
            int x = roadX + oLane[i] * laneW + laneW / 2;
            if (oType[i] == 1) {
                g.setColor(0x5FD16B);
                g.fillRect(x - 6, oY[i] + 8, 12, 8);
                g.setColor(0x101014);
                g.drawRect(x - 6, oY[i] + 8, 12, 8);
            } else {
                drawCar(g, x, oY[i], oColor[i]);
            }
        }
        drawCar(g, roadX + lane * laneW + laneW / 2, carY, 0xE8D84B);

        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Очки " + score, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        g.drawString("$" + cash + "  Рек. " + hi, w - 4, 2, Graphics.TOP | Graphics.RIGHT);
        g.drawString("скорость " + speed, 4, 2 + fS.getHeight(), Graphics.TOP | Graphics.LEFT);

        if (over) banner(g, w, h, "АВАРИЯ", "5 — заново");
        else if (paused) banner(g, w, h, "ПАУЗА", "* — продолжить");
    }

    private void drawCar(Graphics g, int cx, int y, int color) {
        g.setColor(color);
        g.fillRect(cx - 8, y, 16, 28);
        g.setColor(0x101014);
        g.drawRect(cx - 8, y, 16, 28);
        g.fillRect(cx - 6, y + 5, 12, 7);       // windshield
        g.setColor(0x2A2A32);
        g.fillRect(cx - 10, y + 3, 2, 6);
        g.fillRect(cx + 8, y + 3, 2, 6);
        g.fillRect(cx - 10, y + 19, 2, 6);
        g.fillRect(cx + 8, y + 19, 2, 6);
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
