package games.pong;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Ping-pong against the phone. 4/6 - paddle, 5 - serve. First to 11 points. */
public class Pong extends MIDlet implements CommandListener {

    private PongCanvas canvas;
    private Command exitCmd, newCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new PongCanvas();
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

class PongCanvas extends Canvas implements Runnable {

    private static final int BG = 0x101014, INK = 0xE8E0D6, DIM = 0x8A8A94, BALLC = 0xE8D84B;
    private static final int TARGET = 11;

    private int myX, aiX, padW = 40, padH = 5;
    private int bx, by, vx, vy, r = 3;
    private int myScore, aiScore, wins;
    private boolean serve = true, over = false, paused = false;
    private volatile boolean running = false;
    private int level = 3;                 // AI speed

    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    PongCanvas() {
        setFullScreenMode(true);
        wins = loadWins();
        restart();
    }

    void restart() {
        myScore = 0; aiScore = 0; over = false; paused = false;
        int w = getWidth();
        padW = (w > 0 ? w : 240) / 5;
        myX = aiX = ((w > 0 ? w : 240) - padW) / 2;
        newServe();
        repaint();
    }

    private void newServe() {
        int w = getWidth(), h = getHeight();
        bx = (w > 0 ? w : 240) / 2;
        by = (h > 0 ? h : 320) / 2;
        vx = 2; vy = 3;
        serve = true;
    }

    private synchronized void step() {
        if (over || paused) return;
        int w = getWidth(), h = getHeight();
        int topY = fS.getHeight() + 8;
        int myY = h - 14, aiY = topY;

        if (serve) { bx = myX + padW / 2; by = myY - r - 2; return; }

        bx += vx; by += vy;
        if (bx - r < 0) { bx = r; vx = -vx; }
        if (bx + r > w) { bx = w - r; vx = -vx; }

        // my paddle
        if (vy > 0 && by + r >= myY && by + r <= myY + padH + 4 && bx >= myX && bx <= myX + padW) {
            by = myY - r;
            vy = -vy;
            vx += (bx - (myX + padW / 2)) / 8;
            if (vx > 5) vx = 5;
            if (vx < -5) vx = -5;
        }
        // phone's paddle
        if (vy < 0 && by - r <= aiY + padH && by - r >= aiY - 4 && bx >= aiX && bx <= aiX + padW) {
            by = aiY + padH + r;
            vy = -vy;
            vx += (bx - (aiX + padW / 2)) / 8;
            if (vx > 5) vx = 5;
            if (vx < -5) vx = -5;
        }

        // the AI moves its paddle towards the ball, but not instantly - otherwise it is unbeatable
        int aim = bx - padW / 2;
        if (aiX < aim) aiX += level;
        else if (aiX > aim) aiX -= level;
        if (aiX < 0) aiX = 0;
        if (aiX + padW > w) aiX = w - padW;

        if (by - r > h) { aiScore++; check(); newServe(); }
        else if (by + r < 0) { myScore++; check(); newServe(); }
    }

    private void check() {
        if (myScore >= TARGET || aiScore >= TARGET) {
            over = true;
            if (myScore > aiScore) { wins++; saveWins(); }
        }
    }

    public void run() {
        while (running) {
            try { Thread.sleep(26); } catch (Exception e) { break; }
            step();
            repaint();
        }
    }

    void resume() { if (!running) { running = true; new Thread(this).start(); } }
    void pause() { paused = true; }
    void shutdown() { running = false; saveWins(); }
    protected void hideNotify() { paused = true; }

    private int loadWins() {
        try {
            RecordStore rs = RecordStore.openRecordStore("pongwins", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    private void saveWins() {
        try {
            byte[] d = String.valueOf(wins).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("pongwins", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    protected void keyPressed(int keyCode) {
        if (over) { if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) restart(); return; }
        if (keyCode == KEY_STAR) { paused = !paused; repaint(); return; }
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM4 || ga == LEFT) myX -= 12;
        else if (keyCode == KEY_NUM6 || ga == RIGHT) myX += 12;
        else if (keyCode == KEY_NUM5 || ga == FIRE) { serve = false; paused = false; }
        else if (keyCode == KEY_POUND) { level = level >= 5 ? 1 : level + 1; }   // difficulty
        if (myX < 0) myX = 0;
        if (myX + padW > getWidth()) myX = getWidth() - padW;
        repaint();
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (padW == 0 || myX + padW > w) { padW = w / 5; myX = aiX = (w - padW) / 2; }
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Ты " + myScore + " : " + aiScore + " Телефон", w / 2, 2, Graphics.TOP | Graphics.HCENTER);
        g.setColor(DIM);
        g.drawString("Победы " + wins, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.drawString("Ур." + level, w - 4, 2, Graphics.TOP | Graphics.RIGHT);

        int topY = fS.getHeight() + 8, myY = h - 14;
        g.setColor(0x2A2A32);
        for (int y = topY + 20; y < myY - 10; y += 12) g.fillRect(w / 2 - 1, y, 2, 6);

        g.setColor(INK);
        g.fillRect(myX, myY, padW, padH);
        g.setColor(0x4A7BEA);
        g.fillRect(aiX, topY, padW, padH);

        g.setColor(BALLC);
        g.fillArc(bx - r, by - r, r * 2, r * 2, 0, 360);

        if (serve && !over) {
            g.setColor(DIM);
            g.drawString("5 — подача", w / 2, myY - 22, Graphics.TOP | Graphics.HCENTER);
        }
        if (over) banner(g, w, h, myScore > aiScore ? "ТЫ ВЫИГРАЛ" : "ТЫ ПРОИГРАЛ", "5 — заново");
        else if (paused) banner(g, w, h, "ПАУЗА", "* — продолжить");
    }

    private void banner(Graphics g, int w, int h, String title, String hint) {
        int bh = fB.getHeight() + fS.getHeight() + 14;
        int by2 = (h - bh) / 2;
        g.setColor(0x000000);
        g.fillRect(0, by2, w, bh);
        g.setColor(0x2A2A32);
        g.drawRect(0, by2, w - 1, bh - 1);
        g.setFont(fB);
        g.setColor(INK);
        g.drawString(title, w / 2, by2 + 5, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fS);
        g.setColor(DIM);
        g.drawString(hint, w / 2, by2 + 7 + fB.getHeight(), Graphics.TOP | Graphics.HCENTER);
    }
}
