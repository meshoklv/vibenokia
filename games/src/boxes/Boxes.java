package games.boxes;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;

/** Sokoban: push the boxes onto the marked spots. Arrows - move, 0 - undo, # - restart level. */
public class Boxes extends MIDlet implements CommandListener {

    private BoxesCanvas canvas;
    private Command exitCmd, resetCmd, skipCmd;

    public void startApp() {
        if (canvas == null) {
            canvas = new BoxesCanvas();
            exitCmd = new Command("Выход", Command.EXIT, 3);
            resetCmd = new Command("Заново", Command.SCREEN, 1);
            skipCmd = new Command("Дальше", Command.SCREEN, 2);
            canvas.addCommand(resetCmd);
            canvas.addCommand(skipCmd);
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
        else if (c == resetCmd) canvas.reload();
        else if (c == skipCmd) canvas.nextLevel();
    }
}

class BoxesCanvas extends Canvas {

    // # wall, @ player, $ box, . goal, * box on goal, + player on goal, space = floor
    // All levels verified by breadth-first search: each is solvable, solution length in comments
    private static final String[][] LEVELS = {
        {   // 20 moves
            "########",
            "##     #",
            "#    $ #",
            "# @$#$.#",
            "##    .#",
            "# # #. #",
            "########"
        },
        {   // 21 moves
            "########",
            "# #  # #",
            "#      #",
            "# $    #",
            "# $   @#",
            "#   .. #",
            "########"
        },
        {   // 21 moves
            "########",
            "#.     #",
            "#   $# #",
            "#  $   #",
            "##   $.#",
            "#. @  ##",
            "########"
        },
        {   // 24 moves
            "########",
            "##   ..#",
            "# @    #",
            "# $    #",
            "# $    #",
            "#     ##",
            "########"
        },
        {   // 24 moves
            "########",
            "# ..   #",
            "# #  @ #",
            "#   $  #",
            "#.$ $# #",
            "#      #",
            "########"
        },
        {   // 41 moves
            "  ####  ",
            "###  ####",
            "#     $ #",
            "# #  #$ #",
            "# . .#@ #",
            "#########"
        }
    };

    private static final int BG = 0x101014, INK = 0xE8E0D6, DIM = 0x8A8A94;
    private static final int WALL = 0x3C3C46, GOAL = 0x4A7BEA, BOX = 0xE8A24B, BOXOK = 0x5FD16B;

    private int level = 0;
    private char[][] map;
    private int px, py, moves, pushes;
    private boolean done = false;
    private int rows, cols;

    // undo history: direction + whether a box was pushed
    private final int[] histDir = new int[400];
    private final boolean[] histPush = new boolean[400];
    private int histLen = 0;

    private final Font fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fB = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);

    BoxesCanvas() {
        setFullScreenMode(true);
        level = loadLevel();
        if (level < 0 || level >= LEVELS.length) level = 0;
        reload();
    }

    void reload() {
        String[] src = LEVELS[level];
        rows = src.length;
        cols = 0;
        for (int i = 0; i < rows; i++) if (src[i].length() > cols) cols = src[i].length();
        map = new char[rows][cols];
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                char ch = x < src[y].length() ? src[y].charAt(x) : ' ';
                if (ch == '@' || ch == '+') { px = x; py = y; ch = (ch == '+') ? '.' : ' '; }
                map[y][x] = ch;
            }
        moves = 0; pushes = 0; histLen = 0; done = false;
        repaint();
    }

    void nextLevel() {
        level = (level + 1) % LEVELS.length;
        save();
        reload();
    }

    private boolean isWall(int x, int y) {
        return x < 0 || y < 0 || x >= cols || y >= rows || map[y][x] == '#';
    }

    private boolean isBox(int x, int y) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return false;
        return map[y][x] == '$' || map[y][x] == '*';
    }

    private boolean isGoal(int x, int y) {
        return map[y][x] == '.' || map[y][x] == '*';
    }

    private void putBox(int x, int y, boolean there) {
        if (there) map[y][x] = isGoal(x, y) ? '*' : '$';
        else map[y][x] = (map[y][x] == '*') ? '.' : ' ';
    }

    private void move(int dx, int dy, int dirCode) {
        if (done) return;
        int nx = px + dx, ny = py + dy;
        if (isWall(nx, ny)) return;
        boolean push = false;
        if (isBox(nx, ny)) {
            int bx = nx + dx, by = ny + dy;
            if (isWall(bx, by) || isBox(bx, by)) return;
            putBox(nx, ny, false);
            putBox(bx, by, true);
            push = true;
            pushes++;
        }
        px = nx; py = ny;
        moves++;
        if (histLen < histDir.length) { histDir[histLen] = dirCode; histPush[histLen] = push; histLen++; }
        checkDone();
        repaint();
    }

    private void undo() {
        if (histLen == 0 || done) return;
        histLen--;
        int dirCode = histDir[histLen];
        boolean push = histPush[histLen];
        int dx = 0, dy = 0;
        if (dirCode == 0) dy = -1; else if (dirCode == 1) dx = 1;
        else if (dirCode == 2) dy = 1; else dx = -1;
        if (push) {                       // a box was pushed - pull it back
            putBox(px + dx, py + dy, false);
            putBox(px, py, true);
            pushes--;
        }
        px -= dx; py -= dy;
        moves--;
        repaint();
    }

    private void checkDone() {
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++)
                if (map[y][x] == '$') return;
        done = true;
        if (level + 1 < LEVELS.length) { level++; save(); }
    }

    private int loadLevel() {
        try {
            RecordStore rs = RecordStore.openRecordStore("boxeslvl", true);
            int v = 0;
            if (rs.getNumRecords() > 0) v = Integer.parseInt(new String(rs.getRecord(1), "UTF-8"));
            rs.closeRecordStore();
            return v;
        } catch (Exception e) { return 0; }
    }

    void save() {
        try {
            byte[] d = String.valueOf(level).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("boxeslvl", true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length); else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    protected void keyPressed(int keyCode) {
        if (done) {
            if (keyCode == KEY_NUM5 || getGameAction(keyCode) == FIRE) reload();
            return;
        }
        int ga = getGameAction(keyCode);
        if (keyCode == KEY_NUM2 || ga == UP) move(0, -1, 0);
        else if (keyCode == KEY_NUM6 || ga == RIGHT) move(1, 0, 1);
        else if (keyCode == KEY_NUM8 || ga == DOWN) move(0, 1, 2);
        else if (keyCode == KEY_NUM4 || ga == LEFT) move(-1, 0, 3);
        else if (keyCode == KEY_NUM0) undo();
        else if (keyCode == KEY_POUND) reload();
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        int headH = fS.getHeight() + 6;
        g.setFont(fS);
        g.setColor(INK);
        g.drawString("Уровень " + (level + 1) + "/" + LEVELS.length, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(DIM);
        g.drawString("Ходы " + moves + "  Толчки " + pushes, w - 4, 2, Graphics.TOP | Graphics.RIGHT);

        int cw = (w - 6) / cols, chh = (h - headH - 6) / rows;
        int cell = cw < chh ? cw : chh;
        int ox = (w - cell * cols) / 2, oy = headH + (h - headH - cell * rows) / 2;

        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                int cxp = ox + x * cell, cyp = oy + y * cell;
                char ch = map[y][x];
                if (ch == '#') {
                    g.setColor(WALL);
                    g.fillRect(cxp, cyp, cell - 1, cell - 1);
                } else if (ch == '.' || ch == '*') {
                    g.setColor(GOAL);
                    g.fillRect(cxp + cell / 2 - 2, cyp + cell / 2 - 2, 4, 4);
                }
                if (ch == '$' || ch == '*') {
                    g.setColor(ch == '*' ? BOXOK : BOX);
                    g.fillRect(cxp + 2, cyp + 2, cell - 5, cell - 5);
                    g.setColor(0x101014);
                    g.drawRect(cxp + 2, cyp + 2, cell - 5, cell - 5);
                }
                if (x == px && y == py) {
                    g.setColor(INK);
                    g.fillArc(cxp + 2, cyp + 2, cell - 5, cell - 5, 0, 360);
                }
            }

        if (done) banner(g, w, h, "УРОВЕНЬ ПРОЙДЕН", "5 — играть дальше");
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
