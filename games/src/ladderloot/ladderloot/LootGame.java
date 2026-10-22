package ladderloot;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Ladder Loot: grab all the gold on a screen of ladders, ropes and bricks
 * while guards chase you. Dig holes to trap them. Levels are generated so
 * every floor is joined by a ladder and all gold is reachable.
 */
public class LootGame extends Game {
    private static final int C = 16, R = 12, MAXG = 4, HOLE_TIME = 110;

    private final char[] t = new char[C * R];
    private final int[] hole = new int[C * R];
    private int gold, level, lives, ts, ox, oy, deadT, exitOpen;
    // player
    private int px, py, pdx, pdy, pprog, facing = 1;
    // guards
    private final int[] gx = new int[MAXG], gy = new int[MAXG], gdx = new int[MAXG], gdy = new int[MAXG], gprog = new int[MAXG], gtrap = new int[MAXG];
    private int nGuards;
    private Image runner, guard;
    private int spriteTs;

    protected String name() { return "Ladder Loot"; }

    protected String[] help() {
        return new String[] {
            "Collect every piece of gold on the screen. When the last piece is taken, a ladder appears at the top: climb it to escape to the next level.",
            "Guards chase you. Dig a hole in the brick floor beside you to trap them - holes fill back in after a few seconds, so don't stand in one!",
            "You can hang from ropes and drop off them. Stone floors cannot be dug.",
            "- Controls",
            "4/6: run  2/8: climb",
            "1: dig left  3: dig right",
        };
    }

    protected int accent() { return 0xFF9F1C; }

    protected void newGame() {
        level = 0;
        lives = 3;
        buildLevel();
    }

    private int idx(int x, int y) { return y * C + x; }

    private void buildLevel() {
        Rnd.seed(4242 + level * 977);
        for (int i = 0; i < C * R; i++) {
            t[i] = ' ';
            hole[i] = 0;
        }
        for (int x = 0; x < C; x++) t[idx(x, R - 1)] = '=';
        int[] floors = { 2, 5, 8 };
        for (int f = 0; f < 3; f++) {
            int fy = floors[f];
            for (int x = 0; x < C; x++) t[idx(x, fy)] = Rnd.chance(12) ? '=' : '#';
            // one or two ladders from the floor below up through this floor
            int nl = Rnd.chance(55) ? 2 : 1;
            for (int k = 0; k < nl; k++) {
                int lx = Rnd.range(1, C - 2);
                for (int y = fy; y <= fy + 2; y++) t[idx(lx, y)] = 'H';
            }
            if (Rnd.chance(60)) {
                int gxp = Rnd.range(2, C - 4);
                if (t[idx(gxp, fy)] != 'H' && t[idx(gxp + 1, fy)] != 'H') {
                    t[idx(gxp, fy)] = ' ';
                    t[idx(gxp + 1, fy)] = ' ';
                    if (Rnd.chance(50) && fy > 2) for (int x = gxp - 1; x <= gxp + 2; x++) if (t[idx(x, fy - 2)] == ' ') t[idx(x, fy - 2)] = '-';
                }
            }
        }
        int pieces = 6 + Math.min(4, level);
        for (int k = 0; k < pieces; k++) {
            int y = 1 + 3 * Rnd.nextInt(4); // standing rows 1, 4, 7, 10
            int x = Rnd.nextInt(C);
            if (t[idx(x, y)] == ' ' && isSolid(x, y + 1)) t[idx(x, y)] = '$';
        }
        gold = 0;
        for (int i = 0; i < C * R; i++) if (t[i] == '$') gold++;
        int exitX = Rnd.range(2, C - 3);
        t[idx(exitX, 0)] = 'X';
        t[idx(exitX, 1)] = t[idx(exitX, 1)] == '$' ? '$' : 'X';
        exitOpen = 0;
        px = Rnd.range(1, C - 2);
        py = R - 2;
        if (t[idx(px, py)] == '$') { t[idx(px, py)] = ' '; gold--; }
        pdx = pdy = pprog = 0;
        nGuards = Math.min(MAXG, 1 + level / 2 + (level > 0 ? 1 : 0));
        for (int k = 0; k < nGuards; k++) {
            gy[k] = 1 + 3 * Rnd.nextInt(3);
            gx[k] = k % 2 == 0 ? Rnd.range(0, 3) : Rnd.range(C - 4, C - 1);
            gdx[k] = gdy[k] = gprog[k] = 0;
            gtrap[k] = 0;
        }
        deadT = 0;
        Rnd.seed(System.currentTimeMillis());
    }

    // ------------------------------------------------------------ tiles

    private boolean isSolid(int x, int y) {
        if (x < 0 || x >= C || y >= R) return true;
        if (y < 0) return false;
        char c = t[idx(x, y)];
        return (c == '#' && hole[idx(x, y)] == 0) || c == '=';
    }

    private boolean ladder(int x, int y) {
        if (x < 0 || x >= C || y < 0 || y >= R) return false;
        char c = t[idx(x, y)];
        return c == 'H' || (c == 'X' && exitOpen > 0);
    }

    private boolean rope(int x, int y) {
        return x >= 0 && x < C && y >= 0 && y < R && t[idx(x, y)] == '-';
    }

    private boolean guardAt(int x, int y, int except) {
        for (int k = 0; k < nGuards; k++) if (k != except && gx[k] == x && gy[k] == y) return true;
        return false;
    }

    private boolean supported(int x, int y, int self) {
        return ladder(x, y) || rope(x, y) || isSolid(x, y + 1) || ladder(x, y + 1)
                || (y + 1 < R && guardAt(x, y + 1, self) && hole[idx(x, y + 1)] > 0);
    }

    private boolean inHole(int x, int y) {
        return y >= 0 && y < R && x >= 0 && x < C && t[idx(x, y)] == '#' && hole[idx(x, y)] > 0;
    }

    // ------------------------------------------------------------ update

    private int stepTicks() { return 3; }

    private int guardTicks() { return Math.max(4, 7 - level / 2); }

    protected void update() {
        if (deadT > 0) {
            if (--deadT == 0) {
                if (lives <= 0) {
                    endGame(false);
                    return;
                }
                buildLevelKeepGold();
            }
            return;
        }
        for (int i = 0; i < C * R; i++) {
            if (hole[i] > 0 && --hole[i] == 0) {
                int x = i % C, y = i / C;
                if (px == x && py == y) die();
                for (int k = 0; k < nGuards; k++) {
                    if (gx[k] == x && gy[k] == y) {
                        gx[k] = Rnd.nextInt(C);
                        gy[k] = 1;
                        gtrap[k] = 0;
                        gprog[k] = 0;
                        score += 75;
                    }
                }
            }
        }
        movePlayer();
        if (state != PLAY || deadT > 0) return;
        for (int k = 0; k < nGuards; k++) moveGuard(k);
        for (int k = 0; k < nGuards; k++) {
            if (gx[k] == px && gy[k] == py && gtrap[k] == 0) die();
        }
        if (exitOpen > 0) exitOpen++;
    }

    private void buildLevelKeepGold() {
        int s = score;
        buildLevel();
        score = s;
    }

    private void die() {
        if (deadT > 0) return;
        lives--;
        deadT = 30;
        Sfx.bad();
    }

    private void movePlayer() {
        if (pprog > 0) {
            if (++pprog >= stepTicks()) {
                px += pdx;
                py += pdy;
                pprog = 0;
                arrive();
            }
            return;
        }
        if (!supported(px, py, -1)) {
            startMove(0, 1);
            return;
        }
        if ((pressed & (K_NUM0 << 1)) != 0) { dig(px - 1, py + 1); facing = -1; return; }
        if ((pressed & (K_NUM0 << 3)) != 0) { dig(px + 1, py + 1); facing = 1; return; }
        if ((held & K_UP) != 0 && ladder(px, py) && !isSolid(px, py - 1)) {
            if (py == 0) return;
            startMove(0, -1);
        } else if ((held & K_DOWN) != 0 && !isSolid(px, py + 1) && py < R - 1) {
            startMove(0, 1);
        } else if ((held & K_LEFT) != 0 && !isSolid(px - 1, py)) {
            facing = -1;
            startMove(-1, 0);
        } else if ((held & K_RIGHT) != 0 && !isSolid(px + 1, py)) {
            facing = 1;
            startMove(1, 0);
        }
    }

    private void startMove(int dx, int dy) {
        pdx = dx;
        pdy = dy;
        pprog = 1;
    }

    private void arrive() {
        int i = idx(px, py);
        if (t[i] == '$') {
            t[i] = ' ';
            gold--;
            score += 50;
            Sfx.good();
            if (gold == 0) {
                exitOpen = 1;
                for (int y = 0; y < 2; y++) for (int x = 0; x < C; x++) if (t[idx(x, y)] == 'X') t[idx(x, y)] = 'X';
                Sfx.win();
            }
        }
        if (exitOpen > 0 && py == 0) {
            score += 500 + level * 100;
            level++;
            buildLevel();
        }
    }

    private void dig(int x, int y) {
        if (x < 0 || x >= C || y >= R) return;
        if (t[idx(x, y)] != '#' || hole[idx(x, y)] > 0) return;
        if (isSolid(x, y - 1) || ladder(x, y - 1) || t[idx(x, y - 1)] == '$') return;
        hole[idx(x, y)] = HOLE_TIME;
        Sfx.tone(48, 40);
    }

    private void moveGuard(int k) {
        if (gtrap[k] > 0) {
            if (--gtrap[k] == 0 && !isSolid(gx[k], gy[k] - 1)) {
                gy[k]--;
                gx[k] += gx[k] < px ? 1 : (gx[k] > 0 ? -1 : 1);
                if (gx[k] < 0) gx[k] = 0;
                if (gx[k] >= C) gx[k] = C - 1;
                if (isSolid(gx[k], gy[k])) gx[k] = Math.max(0, Math.min(C - 1, gx[k]));
            }
            return;
        }
        if (gprog[k] > 0) {
            if (++gprog[k] >= guardTicks()) {
                gx[k] += gdx[k];
                gy[k] += gdy[k];
                gprog[k] = 0;
                if (inHole(gx[k], gy[k])) gtrap[k] = 60;
            }
            return;
        }
        int x = gx[k], y = gy[k];
        if (!supported(x, y, k)) {
            gStart(k, 0, 1);
            return;
        }
        int dir = 0; // 1 up, 2 down, 3 left, 4 right
        if (py < y) {
            if (ladder(x, y) && !isSolid(x, y - 1)) dir = 1;
            else dir = towardColumn(x, y, true);
        } else if (py > y) {
            if (!isSolid(x, y + 1) && y < R - 1) dir = 2;
            else dir = towardColumn(x, y, false);
        }
        if (dir == 0) dir = px < x ? 3 : (px > x ? 4 : 0);
        int dx = dir == 3 ? -1 : (dir == 4 ? 1 : 0), dy = dir == 1 ? -1 : (dir == 2 ? 1 : 0);
        if (dir == 0) return;
        if ((dx != 0 && isSolid(x + dx, y)) || guardAt(x + dx, y + dy, k)) return;
        gStart(k, dx, dy);
    }

    /** Direction (3 left / 4 right) toward the nearest column offering a way up or down. */
    private int towardColumn(int x, int y, boolean up) {
        for (int d = 1; d < C; d++) {
            for (int s = -1; s <= 1; s += 2) {
                int c = x + d * s;
                if (c < 0 || c >= C) continue;
                if (isSolid(c, y)) continue;
                boolean ok = up ? ladder(c, y) && !isSolid(c, y - 1) : !isSolid(c, y + 1);
                if (ok) return s < 0 ? 3 : 4;
            }
        }
        return 0;
    }

    private void gStart(int k, int dx, int dy) {
        gdx[k] = dx;
        gdy[k] = dy;
        gprog[k] = 1;
    }

    // ------------------------------------------------------------ drawing

    private void sprites() {
        if (runner != null && spriteTs == ts) return;
        spriteTs = ts;
        int sc = Math.max(1, ts / 9);
        runner = Gfx.sprite(new String[] { "..11..", ".1221.", "..11..", ".3333.", "3.33.3", "..33..", ".3..3.", "3....3" }, new int[] { 0, 0xFFCC80, 0xFFFFFF, 0x4FC3F7 }, sc);
        guard = Gfx.sprite(new String[] { "..11..", ".1221.", "..11..", ".3333.", "3.33.3", "..33..", ".3..3.", "3....3" }, new int[] { 0, 0xFFCC80, 0xFF1744, 0xE53935 }, sc);
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        ts = Math.max(6, Math.min(W / C, (H - hud) / R));
        ox = (W - ts * C) / 2;
        oy = hud + (H - hud - ts * R) / 2;
        sprites();
        g.setColor(0x0B0B1E);
        g.fillRect(0, 0, W, H);
        for (int y = 0; y < R; y++) {
            for (int x = 0; x < C; x++) {
                int i = idx(x, y);
                char c = t[i];
                int X = ox + x * ts, Y = oy + y * ts;
                if (c == '#') {
                    if (hole[i] > 0) {
                        if (hole[i] < 20) {
                            g.setColor(0x8D3B2F);
                            int h = ts * (20 - hole[i]) / 20;
                            g.fillRect(X, Y + ts - h, ts, h);
                        }
                        continue;
                    }
                    g.setColor(0xB5523B);
                    g.fillRect(X, Y, ts, ts);
                    g.setColor(0x7A2E20);
                    g.drawLine(X, Y + ts / 2, X + ts - 1, Y + ts / 2);
                    g.drawLine(X + ts / 2, Y, X + ts / 2, Y + ts / 2);
                    g.drawLine(X + ts / 4, Y + ts / 2, X + ts / 4, Y + ts - 1);
                } else if (c == '=') {
                    Gfx.bevel(g, X, Y, ts, ts, 0x78909C);
                } else if (c == 'H' || (c == 'X' && exitOpen > 0)) {
                    g.setColor(c == 'X' ? 0xFFEB3B : 0xBCAAA4);
                    g.fillRect(X + 1, Y, 1, ts);
                    g.fillRect(X + ts - 2, Y, 1, ts);
                    for (int k = 1; k < ts; k += Math.max(2, ts / 3)) g.fillRect(X + 1, Y + k, ts - 2, 1);
                } else if (c == '-') {
                    g.setColor(0xD7CCC8);
                    g.fillRect(X, Y + 1, ts, 1);
                } else if (c == '$') {
                    g.setColor(0xFFC107);
                    g.fillRect(X + ts / 5, Y + ts / 2, ts * 3 / 5, ts / 2 - 1);
                    g.setColor(0xFFE082);
                    g.fillRect(X + ts / 5 + 1, Y + ts / 2 + 1, ts / 5, 1);
                }
            }
        }
        for (int k = 0; k < nGuards; k++) {
            int X = ox + gx[k] * ts + gdx[k] * ts * gprog[k] / guardTicks(), Y = oy + gy[k] * ts + gdy[k] * ts * gprog[k] / guardTicks();
            g.drawImage(guard, X + ts / 2, Y + ts, Graphics.HCENTER | Graphics.BOTTOM);
        }
        if (deadT == 0 || (clock & 2) == 0) {
            int X = ox + px * ts + pdx * ts * pprog / stepTicks(), Y = oy + py * ts + pdy * ts * pprog / stepTicks();
            g.drawRegion(runner, 0, 0, runner.getWidth(), runner.getHeight(), facing < 0 ? 2 : 0, X + ts / 2, Y + ts, Graphics.HCENTER | Graphics.BOTTOM);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFC107);
        g.drawString("$" + gold + " left", 2, 1, Gfx.TL);
        g.setColor(0xFFFFFF);
        g.drawString("L" + (level + 1) + " " + score, W / 2, 1, Gfx.TC);
        g.setColor(0x4FC3F7);
        g.drawString("x" + lives, W - 2, 1, Gfx.TR);
        if (exitOpen > 0 && exitOpen < 60) Gfx.shadowText(g, "ESCAPE UP!", W / 2, oy + ts * 2, Gfx.TC, Gfx.SMALL_B, 0xFFEB3B, 0x000000);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        ts = Math.max(8, h / 4);
        sprites();
        g.setColor(0xB5523B);
        g.fillRect(x, y + h - ts / 2, w, ts / 2);
        g.setColor(0xBCAAA4);
        int lx = x + w * 3 / 4;
        g.fillRect(lx, y, 1, h - ts / 2);
        g.fillRect(lx + ts - 2, y, 1, h - ts / 2);
        for (int k = y + 2; k < y + h - ts / 2; k += 4) g.fillRect(lx, k, ts - 1, 1);
        g.setColor(0xFFC107);
        g.fillRect(x + w / 2, y + h - ts / 2 - 4, 6, 4);
        int r = (clock * 2) % (w / 2);
        g.drawImage(runner, x + r + 4, y + h - ts / 2, Graphics.HCENTER | Graphics.BOTTOM);
        g.drawImage(guard, x + r - ts * 2, y + h - ts / 2, Graphics.HCENTER | Graphics.BOTTOM);
    }
}
