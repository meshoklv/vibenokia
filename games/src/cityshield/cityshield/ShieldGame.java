package cityshield;

import gamekit.FMath;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * City Shield: protect six cities from falling warheads. Aim the crosshair
 * and launch interceptors; their blasts destroy anything they touch.
 */
public class ShieldGame extends Game {
    private static final int MAXM = 20, MAXI = 8, MAXX = 12;
    private int cx, cy, wave, toLaunch, launchT, groundY;
    private final boolean[] city = new boolean[6];
    private final int[] ammo = new int[3];
    // enemy missiles: start, current position (<<8), velocity
    private final int[] msx = new int[MAXM], msy = new int[MAXM], mx = new int[MAXM], my = new int[MAXM], mvx = new int[MAXM], mvy = new int[MAXM];
    private final boolean[] mon = new boolean[MAXM], msplit = new boolean[MAXM];
    // interceptors
    private final int[] ix = new int[MAXI], iy = new int[MAXI], itx = new int[MAXI], ity = new int[MAXI], ivx = new int[MAXI], ivy = new int[MAXI], isx = new int[MAXI], isy = new int[MAXI];
    private final boolean[] ion = new boolean[MAXI];
    // explosions
    private final int[] ex = new int[MAXX], ey = new int[MAXX], et = new int[MAXX];
    private int bonusT;

    protected String name() { return "City Shield"; }

    protected String[] help() {
        return new String[] {
            "Enemy warheads rain down on your six cities. Move the crosshair and press 5: the nearest missile base with ammo fires an interceptor that explodes where you aimed.",
            "Explosions destroy any warhead that flies into them, so lead your targets. Some warheads split in mid-air.",
            "Surviving cities and leftover ammo score a bonus after each wave. Lose all six cities and it's over.",
            "- Controls",
            "2/4/6/8: move crosshair (hold)",
            "1/3/7/9: diagonal  5: fire",
        };
    }

    protected int accent() { return 0xFF5252; }

    protected void newGame() {
        for (int i = 0; i < 6; i++) city[i] = true;
        wave = 0;
        cx = W / 2;
        cy = H / 2;
        startWave();
    }

    private void startWave() {
        wave++;
        for (int i = 0; i < 3; i++) ammo[i] = 10;
        for (int i = 0; i < MAXM; i++) mon[i] = false;
        for (int i = 0; i < MAXI; i++) ion[i] = false;
        for (int i = 0; i < MAXX; i++) et[i] = 0;
        toLaunch = 8 + wave * 3;
        launchT = 30;
        groundY = H - Math.max(10, H / 12);
    }

    private int cityX(int i) {
        int slot = i < 3 ? i + 1 : i + 2; // bases sit at slots 0, 4 and 8
        return W * (2 * slot + 1) / 18;
    }

    private int baseX(int b) { return W * (2 * (b * 4) + 1) / 18; }

    private int targetX(int k) {
        // targets: cities 0..5, bases 6..8
        return k < 6 ? cityX(k) : baseX(k - 6);
    }

    protected void update() {
        int sp = Math.max(2, W / 50);
        if ((held & K_LEFT) != 0 || (held & (K_NUM0 << 1)) != 0 || (held & (K_NUM0 << 7)) != 0) cx -= sp;
        if ((held & K_RIGHT) != 0 || (held & (K_NUM0 << 3)) != 0 || (held & (K_NUM0 << 9)) != 0) cx += sp;
        if ((held & K_UP) != 0 || (held & (K_NUM0 << 1)) != 0 || (held & (K_NUM0 << 3)) != 0) cy -= sp;
        if ((held & K_DOWN) != 0 || (held & (K_NUM0 << 7)) != 0 || (held & (K_NUM0 << 9)) != 0) cy += sp;
        cx = FMath.clamp(cx, 2, W - 3);
        cy = FMath.clamp(cy, Gfx.SMALL.getHeight() + 4, groundY - 12);
        if ((pressed & K_FIRE) != 0) fire();
        if (bonusT > 0) {
            if (--bonusT == 0) startWave();
            return;
        }
        // launch enemy warheads
        if (toLaunch > 0 && --launchT <= 0) {
            launch(Rnd.nextInt(W) << 8, 0, -1);
            toLaunch--;
            launchT = Math.max(8, 40 - wave * 3) + Rnd.nextInt(20);
        }
        int alive = 0;
        for (int i = 0; i < MAXM; i++) {
            if (!mon[i]) continue;
            alive++;
            mx[i] += mvx[i];
            my[i] += mvy[i];
            if (!msplit[i] && wave >= 2 && (my[i] >> 8) > H / 3 && Rnd.chance(2)) {
                msplit[i] = true;
                launch(mx[i], my[i], i);
            }
            if ((my[i] >> 8) >= groundY) {
                mon[i] = false;
                explode(mx[i] >> 8, groundY - 2);
                hitGround(mx[i] >> 8);
            }
        }
        for (int i = 0; i < MAXI; i++) {
            if (!ion[i]) continue;
            ix[i] += ivx[i];
            iy[i] += ivy[i];
            if ((iy[i] >> 8) <= ity[i]) {
                ion[i] = false;
                explode(itx[i], ity[i]);
                Sfx.tone(60, 40);
            }
        }
        for (int k = 0; k < MAXX; k++) {
            if (et[k] == 0) continue;
            et[k]++;
            int r = radius(et[k]);
            if (et[k] > 40) {
                et[k] = 0;
                continue;
            }
            for (int i = 0; i < MAXM; i++) {
                if (!mon[i]) continue;
                int dx = (mx[i] >> 8) - ex[k], dy = (my[i] >> 8) - ey[k];
                if (dx * dx + dy * dy <= r * r) {
                    mon[i] = false;
                    score += 25;
                    explode(mx[i] >> 8, my[i] >> 8);
                    Sfx.tone(72, 20);
                }
            }
        }
        boolean blasts = false;
        for (int k = 0; k < MAXX; k++) if (et[k] > 0) blasts = true;
        if (toLaunch == 0 && alive == 0 && !blasts) endWave();
    }

    private int radius(int t) {
        int max = Math.max(10, W / 11);
        return t < 20 ? max * t / 20 : max * (40 - t) / 20;
    }

    private void launch(int x, int y, int parent) {
        for (int i = 0; i < MAXM; i++) {
            if (mon[i]) continue;
            int tgt = Rnd.nextInt(9);
            if (tgt < 6 && !city[tgt]) tgt = 6 + Rnd.nextInt(3);
            int tx = targetX(tgt) << 8;
            int ty = groundY << 8;
            int frames = Math.max(60, (H * 2) - wave * 12 + Rnd.nextInt(60));
            mon[i] = true;
            msplit[i] = parent >= 0;
            msx[i] = x;
            msy[i] = y;
            mx[i] = x;
            my[i] = y;
            mvx[i] = (tx - x) / frames;
            mvy[i] = Math.max(64, (ty - y) / frames);
            return;
        }
    }

    private void fire() {
        int best = -1, bestD = 9999;
        for (int b = 0; b < 3; b++) {
            if (ammo[b] <= 0) continue;
            int d = Math.abs(baseX(b) - cx);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        if (best < 0) {
            Sfx.bad();
            return;
        }
        for (int i = 0; i < MAXI; i++) {
            if (ion[i]) continue;
            ammo[best]--;
            ion[i] = true;
            isx[i] = baseX(best);
            isy[i] = groundY - 6;
            ix[i] = isx[i] << 8;
            iy[i] = isy[i] << 8;
            itx[i] = cx;
            ity[i] = cy;
            int dx = cx - isx[i], dy = cy - isy[i];
            int dist = Math.max(1, FMath.dist(dx, dy));
            int sp = Math.max(3, H / 40);
            ivx[i] = (dx << 8) * sp / dist;
            ivy[i] = (dy << 8) * sp / dist;
            Sfx.tone(84, 20);
            return;
        }
    }

    private void explode(int x, int y) {
        for (int k = 0; k < MAXX; k++) {
            if (et[k] != 0) continue;
            ex[k] = x;
            ey[k] = y;
            et[k] = 1;
            return;
        }
    }

    private void hitGround(int x) {
        for (int i = 0; i < 6; i++) {
            if (city[i] && Math.abs(cityX(i) - x) < W / 18 + 2) {
                city[i] = false;
                Sfx.bad();
                boolean any = false;
                for (int k = 0; k < 6; k++) any |= city[k];
                if (!any) {
                    headline = "THE CITIES FELL";
                    endGame(false);
                }
                return;
            }
        }
        for (int b = 0; b < 3; b++) if (Math.abs(baseX(b) - x) < W / 18) ammo[b] = 0;
    }

    private void endWave() {
        int c = 0, a = 0;
        for (int i = 0; i < 6; i++) if (city[i]) c++;
        for (int b = 0; b < 3; b++) a += ammo[b];
        score += c * 100 + a * 5;
        bonusT = 50;
        Sfx.win();
    }

    protected void draw(Graphics g) {
        for (int i = 0; i < 6; i++) {
            g.setColor(Gfx.mix(0x0D0221, 0x3A1C71, i * 40));
            g.fillRect(0, i * H / 6, W, H / 6 + 1);
        }
        g.setColor(0x6D4C41);
        g.fillRect(0, groundY, W, H - groundY);
        for (int i = 0; i < 6; i++) {
            int x = cityX(i);
            if (city[i]) {
                g.setColor(0x4FC3F7);
                g.fillRect(x - 6, groundY - 6, 4, 6);
                g.fillRect(x - 2, groundY - 9, 4, 9);
                g.fillRect(x + 2, groundY - 5, 4, 5);
            } else {
                g.setColor(0x3E2723);
                g.fillRect(x - 6, groundY - 2, 12, 2);
            }
        }
        for (int b = 0; b < 3; b++) {
            int x = baseX(b);
            g.setColor(0x8D6E63);
            g.fillTriangle(x - 8, groundY, x + 8, groundY, x, groundY - 8);
            g.setFont(Gfx.SMALL);
            g.setColor(0xFFFFFF);
            g.drawString(String.valueOf(ammo[b]), x, groundY + 1, Gfx.TC);
        }
        for (int i = 0; i < MAXM; i++) {
            if (!mon[i]) continue;
            g.setColor(0xFF5252);
            g.drawLine(msx[i] >> 8, msy[i] >> 8, mx[i] >> 8, my[i] >> 8);
            g.setColor(0xFFFFFF);
            g.fillRect((mx[i] >> 8) - 1, (my[i] >> 8) - 1, 2, 2);
        }
        for (int i = 0; i < MAXI; i++) {
            if (!ion[i]) continue;
            g.setColor(0x69F0AE);
            g.drawLine(isx[i], isy[i], ix[i] >> 8, iy[i] >> 8);
            g.setColor(0xFFFFFF);
            g.drawLine(itx[i] - 2, ity[i] - 2, itx[i] + 2, ity[i] + 2);
            g.drawLine(itx[i] + 2, ity[i] - 2, itx[i] - 2, ity[i] + 2);
        }
        for (int k = 0; k < MAXX; k++) {
            if (et[k] == 0) continue;
            int r = radius(et[k]);
            g.setColor((clock & 2) == 0 ? 0xFFEB3B : 0xFF9800);
            Gfx.disc(g, ex[k], ey[k], Math.max(1, r));
            g.setColor(0xFFFFFF);
            Gfx.disc(g, ex[k], ey[k], Math.max(1, r / 3));
        }
        g.setColor(0x69F0AE);
        g.drawLine(cx - 5, cy, cx - 2, cy);
        g.drawLine(cx + 2, cy, cx + 5, cy);
        g.drawLine(cx, cy - 5, cx, cy - 2);
        g.drawLine(cx, cy + 2, cx, cy + 5);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(score), 2, 1, Gfx.TL);
        g.setColor(0xFF8A80);
        g.drawString("Wave " + wave, W - 2, 1, Gfx.TR);
        if (bonusT > 0) Gfx.shadowText(g, "WAVE CLEARED", W / 2, H / 3, Gfx.TC, Gfx.SMALL_B, 0xFFEB3B, 0x000000);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int t = clock % 50;
        g.setColor(0xFF5252);
        g.drawLine(x + w / 5, y, x + w / 5 + t * w / 200, y + t * h / 50);
        g.setColor(0x69F0AE);
        g.drawLine(x + w * 3 / 4, y + h, x + w / 2, y + h / 2);
        if (t > 25) {
            g.setColor(0xFFEB3B);
            Gfx.disc(g, x + w / 2, y + h / 2, (t - 25) / 2 + 2);
        }
    }
}
