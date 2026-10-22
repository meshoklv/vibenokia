package roadhopper;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Road Hopper: hop a frog across five lanes of traffic and a river of logs
 * and turtles into the five homes at the top.
 */
public class HopperGame extends Game {
    private static final int ROWS = 13, MAXO = 40;
    // row types from the top: 0 homes, 1-5 river, 6 bank, 7-11 road, 12 start
    private final int[] oRow = new int[MAXO], oX = new int[MAXO], oLen = new int[MAXO];
    private int nObj;
    private final int[] rowSpeed = new int[ROWS];
    private final boolean[] home = new boolean[5];
    private int fx, fy, lives, level, timeLeft, deadT, maxRow, cols, cell, ox, oy;

    protected String name() { return "Road Hopper"; }

    protected String[] help() {
        return new String[] {
            "Guide your frog across the busy road, then over the river by hopping on logs and turtles, and into one of the five homes at the top.",
            "Cars squash, water drowns, and you can't ride a log off the edge of the screen. Fill all five homes to clear the level. Watch the timer!",
            "- Controls",
            "2/4/6/8: hop",
        };
    }

    protected int accent() { return 0x43A047; }

    protected void newGame() {
        lives = 3;
        level = 0;
        startLevel();
    }

    private void layout() {
        int hud = Gfx.SMALL.getHeight() + 2;
        cell = Math.max(6, Math.min(W / 11, (H - hud * 2) / ROWS));
        cols = W / cell;
        ox = (W - cols * cell) / 2;
        oy = hud + (H - hud * 2 - cell * ROWS) / 2;
    }

    private void startLevel() {
        layout();
        for (int i = 0; i < 5; i++) home[i] = false;
        nObj = 0;
        for (int r = 1; r < ROWS - 1; r++) {
            if (r == 6) continue;
            boolean river = r <= 5;
            int dir = (r & 1) == 0 ? 1 : -1;
            rowSpeed[r] = dir * (8 + Rnd.nextInt(10) + level * 3) * (river ? 1 : 2) / 2;
            int len = river ? (r == 2 || r == 4 ? 2 : 3 + Rnd.nextInt(2)) : (r == 9 ? 2 : 1);
            int count = river ? 3 : 2 + level / 2;
            int spacing = (cols << 8) / count;
            for (int k = 0; k < count && nObj < MAXO; k++) {
                oRow[nObj] = r;
                oX[nObj] = k * spacing + Rnd.nextInt(spacing / 3 + 1);
                oLen[nObj] = len;
                nObj++;
            }
        }
        respawn();
    }

    private void respawn() {
        fx = (cols / 2) << 8;
        fy = ROWS - 1;
        maxRow = fy;
        timeLeft = 600;
    }

    private boolean onObject(int i, int px) {
        int x = oX[i] >> 8, w = cols;
        for (int k = 0; k < oLen[i]; k++) if (((x + k) % w + w) % w == px) return true;
        return false;
    }

    protected void update() {
        layout();
        if (deadT > 0) {
            if (--deadT == 0) {
                if (lives <= 0) { endGame(false); return; }
                respawn();
            }
            return;
        }
        if (--timeLeft <= 0) { die("TIME UP"); return; }
        for (int i = 0; i < nObj; i++) {
            oX[i] += rowSpeed[oRow[i]];
            int span = cols << 8;
            if (oX[i] < 0) oX[i] += span;
            if (oX[i] >= span) oX[i] -= span;
        }
        int dx = 0, dy = 0;
        if ((pressed & K_UP) != 0) dy = -1;
        else if ((pressed & K_DOWN) != 0) dy = 1;
        else if ((pressed & K_LEFT) != 0) dx = -1;
        else if ((pressed & K_RIGHT) != 0) dx = 1;
        if (dx != 0 || dy != 0) {
            fx += dx << 8;
            fy = Math.max(0, Math.min(ROWS - 1, fy + dy));
            Sfx.tone(70, 12);
            if (fy < maxRow) {
                maxRow = fy;
                score += 10;
            }
        }
        int col = fx >> 8;
        if (col < 0 || col >= cols) { die("SPLASH"); return; }
        if (fy >= 1 && fy <= 5) {
            int ride = -1;
            for (int i = 0; i < nObj; i++) if (oRow[i] == fy && onObject(i, col)) ride = i;
            if (ride < 0) { die("SPLASH!"); return; }
            fx += rowSpeed[fy];
            if ((fx >> 8) < 0 || (fx >> 8) >= cols) { die("SWEPT AWAY"); return; }
        } else if (fy >= 7 && fy <= 11) {
            for (int i = 0; i < nObj; i++) if (oRow[i] == fy && onObject(i, col)) { die("SQUASHED"); return; }
        } else if (fy == 0) {
            int slot = col * 5 / cols;
            int centre = (slot * 2 + 1) * cols / 10;
            if (Math.abs(col - centre) > 0 || home[slot]) { die("MISSED HOME"); return; }
            home[slot] = true;
            score += 50 + timeLeft / 10;
            Sfx.good();
            boolean all = true;
            for (int k = 0; k < 5; k++) all &= home[k];
            if (all) {
                level++;
                score += 500;
                Sfx.win();
                startLevel();
            } else respawn();
        }
    }

    private void die(String why) {
        lives--;
        deadT = 25;
        headline = why;
        Sfx.bad();
    }

    protected void draw(Graphics g) {
        layout();
        g.setColor(0x111111);
        g.fillRect(0, 0, W, H);
        for (int r = 0; r < ROWS; r++) {
            int y = oy + r * cell;
            int c = r == 0 ? 0x1B5E20 : (r <= 5 ? 0x1565C0 : (r == 6 || r == 12 ? 0x7B1FA2 : 0x303030));
            g.setColor(c);
            g.fillRect(ox, y, cols * cell, cell);
            if (r >= 7 && r <= 11 && r < 11) {
                g.setColor(0x9E9E9E);
                for (int x = ox; x < ox + cols * cell; x += cell * 2) g.fillRect(x, y + cell - 1, cell, 1);
            }
        }
        for (int k = 0; k < 5; k++) {
            int centre = (k * 2 + 1) * cols / 10;
            int x = ox + centre * cell;
            g.setColor(0x0D47A1);
            g.fillRect(x, oy, cell, cell);
            if (home[k]) {
                g.setColor(0x76FF03);
                Gfx.disc(g, x + cell / 2, oy + cell / 2, cell / 3);
            }
        }
        for (int i = 0; i < nObj; i++) {
            int r = oRow[i], y = oy + r * cell;
            for (int k = 0; k < oLen[i]; k++) {
                int col = (((oX[i] >> 8) + k) % cols + cols) % cols;
                int x = ox + col * cell;
                if (r <= 5) {
                    if (r == 2 || r == 4) {
                        g.setColor(0x2E7D32);
                        Gfx.disc(g, x + cell / 2, y + cell / 2, cell / 2 - 1);
                        g.setColor(0x81C784);
                        Gfx.disc(g, x + cell / 2, y + cell / 2, cell / 4);
                    } else {
                        g.setColor(0x6D4C41);
                        g.fillRect(x, y + 2, cell, cell - 4);
                        g.setColor(0x8D6E63);
                        g.fillRect(x, y + 3, cell, 1);
                    }
                } else {
                    int cc = r % 3 == 0 ? 0xFFEB3B : (r % 3 == 1 ? 0xE53935 : 0x29B6F6);
                    Gfx.bevel(g, x + 1, y + 2, cell - 2, cell - 4, cc);
                    g.setColor(0x212121);
                    g.fillRect(x + (rowSpeed[r] > 0 ? cell - 3 : 1), y + 3, 2, cell - 6);
                }
            }
        }
        if (deadT == 0 || (clock & 2) == 0) {
            int x = ox + ((fx * cell) >> 8), y = oy + fy * cell;
            g.setColor(deadT > 0 ? 0xFF5252 : 0x76FF03);
            Gfx.disc(g, x + cell / 2, y + cell / 2, cell / 2 - 1);
            g.setColor(0x000000);
            g.fillRect(x + cell / 3 - 1, y + cell / 3, 2, 2);
            g.fillRect(x + cell * 2 / 3, y + cell / 3, 2, 2);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(score), 2, 1, Gfx.TL);
        g.setColor(0x76FF03);
        g.drawString("x" + lives + " L" + (level + 1), W - 2, 1, Gfx.TR);
        Gfx.bar(g, W / 4, H - Gfx.SMALL.getHeight(), W / 2, 5, timeLeft, 600, 0xFFEB3B, 0x303030);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        g.setColor(0x303030);
        g.fillRect(x, y + h / 3, w, h / 3);
        int cx = x + (clock * 3) % (w + 20) - 10;
        Gfx.bevel(g, cx, y + h / 3 + 2, 14, h / 3 - 4, 0xE53935);
        int hop = (clock / 8) % 3;
        g.setColor(0x76FF03);
        Gfx.disc(g, x + w / 2, y + h - h / 6 - hop * h / 3, 5);
    }
}
