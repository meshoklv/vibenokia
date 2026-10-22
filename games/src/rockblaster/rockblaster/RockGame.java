package rockblaster;

import gamekit.FMath;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Rock Blaster: rotate, thrust and shoot drifting space rocks into gravel. */
public class RockGame extends Game {
    private static final int MAXR = 28, MAXB = 6, VERTS = 9;

    private int sx, sy, svx, svy, ang, lives, wave, invuln, respawn, nextLife;
    private boolean thrusting;
    private final int[] bx = new int[MAXB], by = new int[MAXB], bvx = new int[MAXB], bvy = new int[MAXB], blife = new int[MAXB];
    private final int[] rx = new int[MAXR], ry = new int[MAXR], rvx = new int[MAXR], rvy = new int[MAXR], rsize = new int[MAXR], rspin = new int[MAXR];
    private final boolean[] ron = new boolean[MAXR];
    private final byte[][] shape = new byte[MAXR][VERTS];
    // saucer
    private int ux, uy, uvx, utimer, ushot, ubx, uby, ubvx, ubvy, ublife;
    private boolean uon;
    private final int[] px = new int[VERTS + 1], py = new int[VERTS + 1];

    protected String name() { return "Rock Blaster"; }

    protected String[] help() {
        return new String[] {
            "Your ship floats in a field of drifting rocks. Shoot them: big rocks split into medium ones, medium into small, small ones turn to dust.",
            "Space wraps around the screen edges and your ship keeps drifting, so use thrust carefully. Watch out for the saucer, it shoots back.",
            "Hyperspace teleports you somewhere random, possibly somewhere worse. Extra ship every 10000 points.",
            "- Controls",
            "4/6: rotate  2: thrust",
            "5: fire  8 or 0: hyperspace",
        };
    }

    protected int accent() { return 0xB0BEC5; }

    protected void newGame() {
        lives = 3;
        wave = 0;
        nextLife = 10000;
        for (int i = 0; i < MAXB; i++) blife[i] = 0;
        resetShip();
        startWave();
    }

    private int bigR() { return Math.max(8, Math.min(W, H) / 8); }

    private int radius(int size) {
        return size == 3 ? bigR() : (size == 2 ? bigR() * 55 / 100 : bigR() * 30 / 100);
    }

    private void resetShip() {
        sx = (W / 2) << 8;
        sy = (H / 2) << 8;
        svx = svy = 0;
        ang = 192;
        invuln = 50;
    }

    private void startWave() {
        for (int i = 0; i < MAXR; i++) ron[i] = false;
        int n = Math.min(8, 3 + wave);
        for (int k = 0; k < n; k++) {
            int x, y;
            do {
                x = Rnd.nextInt(W);
                y = Rnd.nextInt(H);
            } while (Math.abs(x - (sx >> 8)) < W / 4 && Math.abs(y - (sy >> 8)) < H / 4);
            spawnRock(x << 8, y << 8, 3);
        }
        uon = false;
        utimer = 400;
    }

    private void spawnRock(int x, int y, int size) {
        for (int i = 0; i < MAXR; i++) {
            if (ron[i]) continue;
            ron[i] = true;
            rx[i] = x;
            ry[i] = y;
            rsize[i] = size;
            int a = Rnd.nextInt(256);
            int sp = ((W << 8) / 160) * (4 - size + wave / 3) / 2 + 40;
            rvx[i] = FMath.cos(a) * sp >> 10;
            rvy[i] = FMath.sin(a) * sp >> 10;
            rspin[i] = Rnd.range(0, 255);
            for (int k = 0; k < VERTS; k++) shape[i][k] = (byte) Rnd.range(68, 100);
            return;
        }
    }

    private int wrapX(int v) {
        int w = W << 8;
        if (v < 0) v += w;
        if (v >= w) v -= w;
        return v;
    }

    private int wrapY(int v) {
        int h = H << 8;
        if (v < 0) v += h;
        if (v >= h) v -= h;
        return v;
    }

    protected void update() {
        if (respawn > 0) {
            if (--respawn == 0) {
                if (lives <= 0) {
                    endGame(false);
                    return;
                }
                resetShip();
            }
        } else {
            if ((held & (K_LEFT | (K_NUM0 << 1))) != 0) ang = (ang - 8) & 255;
            if ((held & (K_RIGHT | (K_NUM0 << 3))) != 0) ang = (ang + 8) & 255;
            thrusting = (held & K_UP) != 0;
            int thrust = (W << 8) / 900;
            if (thrusting) {
                svx += FMath.cos(ang) * thrust >> 10;
                svy += FMath.sin(ang) * thrust >> 10;
            }
            svx = svx * 252 / 256;
            svy = svy * 252 / 256;
            int max = (W << 8) / 40;
            svx = FMath.clamp(svx, -max, max);
            svy = FMath.clamp(svy, -max, max);
            sx = wrapX(sx + svx);
            sy = wrapY(sy + svy);
            if ((pressed & K_FIRE) != 0) fire();
            if ((pressed & (K_DOWN | K_NUM0)) != 0) {
                sx = Rnd.nextInt(W) << 8;
                sy = Rnd.nextInt(H) << 8;
                svx = svy = 0;
                Sfx.tone(90, 40);
            }
            if (invuln > 0) invuln--;
        }
        for (int i = 0; i < MAXB; i++) {
            if (blife[i] == 0) continue;
            blife[i]--;
            bx[i] = wrapX(bx[i] + bvx[i]);
            by[i] = wrapY(by[i] + bvy[i]);
        }
        int alive = 0;
        for (int i = 0; i < MAXR; i++) {
            if (!ron[i]) continue;
            alive++;
            rx[i] = wrapX(rx[i] + rvx[i]);
            ry[i] = wrapY(ry[i] + rvy[i]);
            rspin[i] = (rspin[i] + 2) & 255;
            int r = radius(rsize[i]);
            for (int b = 0; b < MAXB; b++) {
                if (blife[b] == 0) continue;
                int dx = (bx[b] - rx[i]) >> 8, dy = (by[b] - ry[i]) >> 8;
                if (dx * dx + dy * dy <= r * r) {
                    blife[b] = 0;
                    breakRock(i);
                    break;
                }
            }
            if (ron[i] && respawn == 0 && invuln == 0) {
                int dx = (sx - rx[i]) >> 8, dy = (sy - ry[i]) >> 8, rr = r + bigR() / 5;
                if (dx * dx + dy * dy <= rr * rr) {
                    breakRock(i);
                    shipHit();
                }
            }
        }
        saucer();
        if (score >= nextLife) {
            lives++;
            nextLife += 10000;
            Sfx.good();
        }
        if (alive == 0 && respawn == 0) {
            wave++;
            Sfx.win();
            startWave();
        }
    }

    private void fire() {
        for (int i = 0; i < MAXB; i++) {
            if (blife[i] != 0) continue;
            int sp = (W << 8) / 22;
            bx[i] = sx + (FMath.cos(ang) * bigR() / 2 >> 2);
            by[i] = sy + (FMath.sin(ang) * bigR() / 2 >> 2);
            bvx[i] = svx + (FMath.cos(ang) * sp >> 10);
            bvy[i] = svy + (FMath.sin(ang) * sp >> 10);
            blife[i] = 16;
            Sfx.tone(88, 10);
            return;
        }
    }

    private void breakRock(int i) {
        ron[i] = false;
        int s = rsize[i];
        score += s == 3 ? 20 : (s == 2 ? 50 : 100);
        Sfx.tone(40 + s * 6, 40);
        if (s > 1) {
            spawnRock(rx[i], ry[i], s - 1);
            spawnRock(rx[i], ry[i], s - 1);
        }
    }

    private void shipHit() {
        lives--;
        respawn = 40;
        Sfx.bad();
    }

    private void saucer() {
        if (ublife > 0) {
            ublife--;
            ubx = wrapX(ubx + ubvx);
            uby = wrapY(uby + ubvy);
            if (respawn == 0 && invuln == 0 && Math.abs((ubx - sx) >> 8) < 5 && Math.abs((uby - sy) >> 8) < 5) {
                ublife = 0;
                shipHit();
            }
        }
        if (!uon) {
            if (--utimer <= 0) {
                uon = true;
                ux = 0;
                uy = Rnd.range(H / 6, H * 5 / 6) << 8;
                uvx = (W << 8) / 150;
                ushot = 30;
            }
            return;
        }
        ux += uvx;
        uy = wrapY(uy + (FMath.sin(clock * 4) * 120 >> 10));
        if ((ux >> 8) > W) {
            uon = false;
            utimer = 500;
            return;
        }
        if (--ushot <= 0 && respawn == 0) {
            ushot = 35 - Math.min(20, wave * 2);
            int a = FMath.atan2((sy - uy) >> 8, (sx - ux) >> 8) + Rnd.range(-12, 12);
            int sp = (W << 8) / 45;
            ubx = ux;
            uby = uy;
            ubvx = FMath.cos(a) * sp >> 10;
            ubvy = FMath.sin(a) * sp >> 10;
            ublife = 40;
            Sfx.tone(70, 20);
        }
        for (int b = 0; b < MAXB; b++) {
            if (blife[b] == 0) continue;
            if (Math.abs((bx[b] - ux) >> 8) < bigR() / 2 && Math.abs((by[b] - uy) >> 8) < bigR() / 3) {
                blife[b] = 0;
                uon = false;
                utimer = 500;
                score += 500;
                Sfx.good();
                return;
            }
        }
    }

    protected void draw(Graphics g) {
        g.setColor(0x000008);
        g.fillRect(0, 0, W, H);
        g.setColor(0x262A40);
        for (int i = 0; i < 25; i++) g.fillRect((i * 71 + 11) % W, (i * 131 + 7) % H, 1, 1);
        g.setColor(0xCFD8DC);
        for (int i = 0; i < MAXR; i++) {
            if (!ron[i]) continue;
            int r = radius(rsize[i]);
            int cx = rx[i] >> 8, cy = ry[i] >> 8;
            for (int k = 0; k <= VERTS; k++) {
                int a = (rspin[i] + (k % VERTS) * 256 / VERTS) & 255;
                int rr = r * shape[i][k % VERTS] / 100;
                px[k] = cx + (FMath.cos(a) * rr >> 10);
                py[k] = cy + (FMath.sin(a) * rr >> 10);
            }
            for (int k = 0; k < VERTS; k++) g.drawLine(px[k], py[k], px[k + 1], py[k + 1]);
        }
        g.setColor(0xFFFFFF);
        for (int i = 0; i < MAXB; i++) if (blife[i] > 0) g.fillRect((bx[i] >> 8) - 1, (by[i] >> 8) - 1, 2, 2);
        if (uon) {
            int x = ux >> 8, y = uy >> 8, s = bigR() / 2;
            g.setColor(0x80FF80);
            g.drawLine(x - s, y, x + s, y);
            g.drawLine(x - s, y, x - s / 2, y + s / 3);
            g.drawLine(x + s, y, x + s / 2, y + s / 3);
            g.drawLine(x - s / 2, y + s / 3, x + s / 2, y + s / 3);
            g.drawLine(x - s / 2, y, x - s / 3, y - s / 3);
            g.drawLine(x + s / 2, y, x + s / 3, y - s / 3);
            g.drawLine(x - s / 3, y - s / 3, x + s / 3, y - s / 3);
        }
        if (ublife > 0) {
            g.setColor(0xFF5252);
            g.fillRect((ubx >> 8) - 1, (uby >> 8) - 1, 3, 3);
        }
        if (respawn == 0 && (invuln == 0 || (clock & 2) == 0)) drawShip(g, sx >> 8, sy >> 8, ang, bigR() / 2 + 3, thrusting);
        else if (respawn > 0) {
            g.setColor(0xFFAB40);
            int t = 40 - respawn;
            for (int k = 0; k < 8; k++) {
                int a = k * 32;
                g.fillRect((sx >> 8) + (FMath.cos(a) * t >> 9), (sy >> 8) + (FMath.sin(a) * t >> 9), 2, 2);
            }
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(score), 2, 1, Gfx.TL);
        for (int i = 0; i < lives; i++) drawShip(g, W - 6 - i * 9, 6, 192, 5, false);
    }

    private void drawShip(Graphics g, int x, int y, int a, int s, boolean flame) {
        int nx = x + (FMath.cos(a) * s >> 10), ny = y + (FMath.sin(a) * s >> 10);
        int lx = x + (FMath.cos(a + 102) * s * 3 / 4 >> 10), ly = y + (FMath.sin(a + 102) * s * 3 / 4 >> 10);
        int rx2 = x + (FMath.cos(a - 102) * s * 3 / 4 >> 10), ry2 = y + (FMath.sin(a - 102) * s * 3 / 4 >> 10);
        int bx2 = x - (FMath.cos(a) * s / 3 >> 10), by2 = y - (FMath.sin(a) * s / 3 >> 10);
        g.setColor(0x80D8FF);
        g.drawLine(nx, ny, lx, ly);
        g.drawLine(nx, ny, rx2, ry2);
        g.drawLine(lx, ly, bx2, by2);
        g.drawLine(rx2, ry2, bx2, by2);
        if (flame && (clock & 1) == 0) {
            int fx = x - (FMath.cos(a) * s >> 10), fy = y - (FMath.sin(a) * s >> 10);
            g.setColor(0xFFAB40);
            g.drawLine(bx2, by2, fx, fy);
        }
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int r = Math.max(8, h / 3);
        g.setColor(0xCFD8DC);
        int cx = x + w / 3, cy = y + h / 2;
        for (int k = 0; k < VERTS; k++) {
            int a0 = (clock * 2 + k * 256 / VERTS) & 255, a1 = (clock * 2 + (k + 1) * 256 / VERTS) & 255;
            int r0 = r * (80 + (k * 37) % 20) / 100, r1 = r * (80 + ((k + 1) % VERTS * 37) % 20) / 100;
            g.drawLine(cx + (FMath.cos(a0) * r0 >> 10), cy + (FMath.sin(a0) * r0 >> 10), cx + (FMath.cos(a1) * r1 >> 10), cy + (FMath.sin(a1) * r1 >> 10));
        }
        drawShip(g, x + w * 3 / 4, y + h / 2, 128, Math.max(6, h / 4), (clock & 4) == 0);
        g.setColor(0xFFFFFF);
        int t = (clock * 4) % (w / 2);
        g.fillRect(x + w * 3 / 4 - t - 8, y + h / 2, 2, 2);
    }
}
