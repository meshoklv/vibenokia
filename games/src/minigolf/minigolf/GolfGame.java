package minigolf;

import gamekit.FMath;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Mini Golf: nine top-down holes with sand, water and bumpers. Aim, set power, putt. */
public class GolfGame extends Game {
    private static final int TW = 12, TH = 15, ONE = 1024, R = 190;
    private static final String[][] HOLES = {
        { "############", "#          #", "#    H     #", "#          #", "#          #", "#          #", "#          #", "#          #",
          "#          #", "#          #", "#          #", "#          #", "#    T     #", "#          #", "############" },
        { "############", "#######  H #", "#######    #", "#######    #", "#######    #", "#######    #", "#          #", "#          #",
          "#  SS      #", "#  SS      #", "#          #", "#          #", "# T        #", "#          #", "############" },
        { "############", "#    H     #", "#          #", "#          #", "#WWWWW  WWW#", "#WWWWW  WWW#", "#          #", "#          #",
          "#   SSS    #", "#          #", "#          #", "#          #", "#     T    #", "#          #", "############" },
        { "############", "#  H       #", "#          #", "#   B   B  #", "#          #", "# ######## #", "#          #", "#  B    B  #",
          "#          #", "#######    #", "#          #", "#          #", "#        T #", "#          #", "############" },
        { "############", "# H        #", "#          #", "#######    #", "#          #", "#          #", "#    #######", "#          #",
          "#          #", "#######    #", "#          #", "#          #", "#   T      #", "#          #", "############" },
        { "############", "#WWWWWWWWWW#", "#WWW    WWW#", "#WW  H   WW#", "#WWW    WWW#", "#WWWW  WWWW#", "#          #", "#          #",
          "#   SS SS  #", "#          #", "#          #", "#          #", "#    T     #", "#          #", "############" },
        { "############", "#SSSSSSSSSS#", "#SSS H  SSS#", "#SS      SS#", "#S   ##   S#", "#    ##    #", "#          #", "#  B    B  #",
          "#          #", "#    ##    #", "#    ##    #", "#          #", "#  T       #", "#          #", "############" },
        { "############", "#        H #", "#          #", "# ######## #", "#          #", "#WWWW  WWWW#", "#          #", "#  B  B  B #",
          "#          #", "# ######## #", "#          #", "#SS      SS#", "#    T     #", "#          #", "############" },
        { "############", "#          #", "#  ######  #", "#  #    #  #", "#  #  H #  #", "#  #    #  #", "#  ### ##  #", "#          #",
          "# B      B #", "#          #", "#WWW    WWW#", "#          #", "#    T     #", "#          #", "############" },
    };
    private static final int[] PAR = { 2, 3, 3, 3, 4, 3, 3, 4, 4 };
    private static final int AIM = 0, POWER = 1, ROLL = 2, SUNK = 3;

    private int hole, strokes, total, phase, angle, power, powerDir, timer;
    private int bx, by, vx, vy, lastX, lastY, holeX, holeY;
    private String note = "";
    private final int[] cardScores = new int[9];

    protected String name() { return "Mini Golf"; }

    protected String[] help() {
        return new String[] {
            "Nine holes of top-down crazy golf. Rotate your aim with 4 and 6, press 5 to start the power meter, and 5 again to putt.",
            "Walls bounce the ball, red bumpers fire it back faster, sand slows it down and water costs a stroke and returns you to where you putted from.",
            "A ball rolling too fast skips over the cup. Finish the course in as few strokes as possible.",
            "- Controls",
            "4/6: aim (hold)  1/3: aim fine",
            "5: start / stop the power meter",
        };
    }

    protected boolean lowerIsBetter() { return true; }

    protected String formatScore(int s) {
        int par = 0;
        for (int i = 0; i < PAR.length; i++) par += PAR[i];
        int d = s - par;
        return s + " (" + (d > 0 ? "+" : "") + d + ")";
    }

    protected int accent() { return 0x52B788; }

    protected void newGame() {
        hole = 0;
        total = 0;
        startHole();
    }

    private char tile(int x, int y) {
        int tx = x / ONE, ty = y / ONE;
        if (x < 0 || y < 0 || tx >= TW || ty >= TH) return '#';
        return HOLES[hole][ty].charAt(tx);
    }

    private void startHole() {
        strokes = 0;
        phase = AIM;
        angle = 192;
        for (int y = 0; y < TH; y++) {
            for (int x = 0; x < TW; x++) {
                char c = HOLES[hole][y].charAt(x);
                if (c == 'T') { bx = x * ONE + ONE / 2; by = y * ONE + ONE / 2; }
                if (c == 'H') { holeX = x * ONE + ONE / 2; holeY = y * ONE + ONE / 2; }
            }
        }
        angle = FMath.atan2(holeY - by, holeX - bx);
        note = "Hole " + (hole + 1) + "  Par " + PAR[hole];
    }

    private boolean solidAt(int x, int y) {
        char c = tile(x, y);
        return c == '#' || c == 'B';
    }

    protected void update() {
        switch (phase) {
            case AIM:
                if ((held & K_LEFT) != 0) angle = (angle - 2) & 255;
                if ((held & K_RIGHT) != 0) angle = (angle + 2) & 255;
                if (digit(1)) angle = (angle - 1) & 255;
                if (digit(3)) angle = (angle + 1) & 255;
                if ((pressed & K_FIRE) != 0) {
                    phase = POWER;
                    power = 0;
                    powerDir = 1;
                }
                break;
            case POWER:
                power += powerDir * 3;
                if (power >= 100) { power = 100; powerDir = -1; }
                if (power <= 0) { power = 0; powerDir = 1; }
                if ((pressed & K_FIRE) != 0) {
                    int sp = 40 + power * 4;
                    vx = FMath.cos(angle) * sp >> 10;
                    vy = FMath.sin(angle) * sp >> 10;
                    lastX = bx;
                    lastY = by;
                    strokes++;
                    phase = ROLL;
                    note = "";
                    Sfx.tone(60, 30);
                }
                break;
            case ROLL:
                roll();
                break;
            default:
                if (--timer <= 0 || (pressed & K_FIRE) != 0) {
                    cardScores[hole] = strokes;
                    total += strokes;
                    hole++;
                    score = total;
                    if (hole >= HOLES.length) {
                        headline = "ROUND COMPLETE";
                        endGame(true);
                        return;
                    }
                    startHole();
                }
                break;
        }
        score = total + (phase == SUNK ? strokes : 0);
    }

    private void roll() {
        for (int sub = 0; sub < 2; sub++) {
            int nx = bx + vx / 2;
            if (solidAt(nx + (vx > 0 ? R : -R), by)) {
                boolean bump = tile(nx + (vx > 0 ? R : -R), by) == 'B';
                vx = -vx * (bump ? 12 : 8) / 10;
                Sfx.tone(bump ? 84 : 70, 15);
            } else bx = nx;
            int ny = by + vy / 2;
            if (solidAt(bx, ny + (vy > 0 ? R : -R))) {
                boolean bump = tile(bx, ny + (vy > 0 ? R : -R)) == 'B';
                vy = -vy * (bump ? 12 : 8) / 10;
                Sfx.tone(bump ? 84 : 70, 15);
            } else by = ny;
        }
        char under = tile(bx, by);
        int fr = under == 'S' ? 214 : 246;
        vx = vx * fr / 256;
        vy = vy * fr / 256;
        int speed = Math.abs(vx) + Math.abs(vy);
        int dx = bx - holeX, dy = by - holeY;
        if (dx * dx + dy * dy < 330 * 330 && speed < 260) {
            phase = SUNK;
            timer = 40;
            int d = strokes - PAR[hole];
            note = strokes == 1 ? "HOLE IN ONE!" : (d <= -2 ? "EAGLE!" : (d == -1 ? "BIRDIE!" : (d == 0 ? "PAR" : (d == 1 ? "Bogey" : "+" + d))));
            Sfx.win();
            return;
        }
        if (under == 'W') {
            strokes++;
            bx = lastX;
            by = lastY;
            vx = vy = 0;
            note = "Splash! +1 stroke";
            Sfx.bad();
        }
        if (Math.abs(vx) < 8 && Math.abs(vy) < 8) {
            vx = vy = 0;
            phase = AIM;
            if (strokes >= 10) {
                strokes = 11;
                phase = SUNK;
                timer = 30;
                note = "Max strokes";
            }
        }
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int t = Math.max(6, Math.min(W / TW, (H - hud * 2) / TH));
        int ox = (W - t * TW) / 2, oy = hud + (H - hud * 2 - t * TH) / 2;
        g.setColor(0x1B4332);
        g.fillRect(0, 0, W, H);
        for (int y = 0; y < TH; y++) {
            for (int x = 0; x < TW; x++) {
                char c = HOLES[hole][y].charAt(x);
                int px = ox + x * t, py = oy + y * t;
                switch (c) {
                    case '#': Gfx.bevel(g, px, py, t, t, 0x8D6E63); break;
                    case 'W':
                        g.setColor(((x + y + clock / 8) & 1) == 0 ? 0x1E88E5 : 0x1976D2);
                        g.fillRect(px, py, t, t);
                        break;
                    case 'S':
                        g.setColor(0xE9D8A6);
                        g.fillRect(px, py, t, t);
                        g.setColor(0xD4B483);
                        g.fillRect(px + t / 3, py + t / 2, 1, 1);
                        break;
                    case 'B':
                        g.setColor(0x52B788);
                        g.fillRect(px, py, t, t);
                        g.setColor(0xD62828);
                        Gfx.disc(g, px + t / 2, py + t / 2, t / 2 - 1);
                        g.setColor(0xF77F00);
                        Gfx.disc(g, px + t / 2, py + t / 2, t / 4);
                        break;
                    default:
                        g.setColor(((x + y) & 1) == 0 ? 0x52B788 : 0x4CAF7D);
                        g.fillRect(px, py, t, t);
                        break;
                }
            }
        }
        int hx = ox + holeX * t / ONE, hy = oy + holeY * t / ONE;
        g.setColor(0x081C15);
        Gfx.disc(g, hx, hy, Math.max(2, t * 2 / 5));
        if (phase != SUNK) {
            g.setColor(0xFFFFFF);
            g.fillRect(hx, hy - t * 3 / 2, 1, t * 3 / 2);
            g.setColor(0xFF1744);
            g.fillTriangle(hx + 1, hy - t * 3 / 2, hx + 1, hy - t, hx + t * 2 / 3, hy - t * 5 / 4);
        }
        int px = ox + bx * t / ONE, py = oy + by * t / ONE;
        if (phase == AIM || phase == POWER) {
            g.setColor(0xFFFFFF);
            g.setStrokeStyle(Graphics.DOTTED);
            int len = t * 3;
            g.drawLine(px, py, px + (FMath.cos(angle) * len >> 10), py + (FMath.sin(angle) * len >> 10));
            g.setStrokeStyle(Graphics.SOLID);
        }
        if (phase != SUNK) {
            g.setColor(0x000000);
            Gfx.disc(g, px + 1, py + 1, Math.max(2, t * R / ONE + 1));
            g.setColor(0xFFFFFF);
            Gfx.disc(g, px, py, Math.max(2, t * R / ONE + 1));
        }
        g.setColor(0x081C15);
        g.fillRect(0, 0, W, hud);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("Hole " + (hole + 1) + "/9  Par " + PAR[hole], 2, 1, Gfx.TL);
        g.setColor(0xFFD166);
        g.drawString("S " + strokes, W - 2, 1, Gfx.TR);
        int by2 = H - hud;
        if (phase == POWER) {
            Gfx.bar(g, W / 6, by2 + 2, W * 2 / 3, hud - 4, power, 100, power > 80 ? 0xFF5252 : 0xFFD166, 0x263238);
        } else {
            g.setFont(Gfx.SMALL);
            g.setColor(0xD8F3DC);
            g.drawString(note.length() > 0 ? note : "Total " + total, W / 2, by2 + 1, Gfx.TC);
        }
        if (phase == SUNK) Gfx.shadowText(g, note, W / 2, H / 2 - 8, Gfx.TC, Gfx.fit(note, W - 8), 0xFFD166, 0x000000);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        g.setColor(0x52B788);
        g.fillRoundRect(x + 4, y + 2, w - 8, h - 4, 12, 12);
        int hx = x + w * 3 / 4, hy = y + h / 2;
        g.setColor(0x081C15);
        Gfx.disc(g, hx, hy, 4);
        g.setColor(0xFFFFFF);
        g.fillRect(hx, hy - 14, 1, 14);
        g.setColor(0xFF1744);
        g.fillTriangle(hx + 1, hy - 14, hx + 1, hy - 9, hx + 8, hy - 11);
        int t = clock % 40;
        int bxp = x + w / 5 + (hx - x - w / 5) * Math.min(t, 30) / 30;
        g.setColor(0xFFFFFF);
        if (t < 30) Gfx.disc(g, bxp, hy, 3);
    }
}
