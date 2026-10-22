package coptercave;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Copter Cave: hold the key to climb, let go to sink. Thread an endless,
 * narrowing cave and dodge the rock pillars.
 */
public class CopterGame extends Game {
    private static final int COLS = 64;
    private final int[] top = new int[COLS], bot = new int[COLS], block = new int[COLS];
    private int head, scroll, colW, cy, vy, dist, deadT, centre, gap, trailN;
    private final int[] trail = new int[10];

    protected String name() { return "Copter Cave"; }

    protected String[] help() {
        return new String[] {
            "Fly your helicopter through an endless cave. Hold 5 (or 2) to climb; release to sink.",
            "Touching the cave walls or the rock pillars ends the flight. The cave gets narrower the further you fly.",
            "- Controls",
            "5 or 2 (hold): climb",
        };
    }

    protected int accent() { return 0x8BC34A; }

    protected void newGame() {
        colW = Math.max(4, W / 30);
        int hud = Gfx.SMALL.getHeight() + 2;
        centre = hud + (H - hud) / 2;
        gap = (H - hud) * 3 / 4;
        dist = 0;
        for (int i = 0; i < COLS; i++) gen(i);
        head = 0;
        scroll = 0;
        cy = centre << 8;
        vy = 0;
        deadT = 0;
        trailN = 0;
    }

    private void gen(int i) {
        int hud = Gfx.SMALL.getHeight() + 2;
        centre += Rnd.range(-3, 3);
        gap = Math.max((H - hud) / 3, (H - hud) * 3 / 4 - dist / 60);
        centre = Math.max(hud + gap / 2 + 2, Math.min(H - gap / 2 - 2, centre));
        top[i] = centre - gap / 2;
        bot[i] = centre + gap / 2;
        block[i] = (dist > 300 && Rnd.chance(5)) ? Rnd.range(top[i] + gap / 5, bot[i] - gap / 3) : 0;
    }

    protected void update() {
        if (deadT > 0) {
            if (--deadT == 0) endGame(false);
            return;
        }
        int sp = 2 + dist / 1500;
        scroll += sp;
        dist += sp;
        while (scroll >= colW) {
            scroll -= colW;
            gen(head);
            head = (head + 1) % COLS;
        }
        boolean up = (held & (K_FIRE | K_UP)) != 0;
        vy += up ? -40 : 40;
        vy = Math.max(-500, Math.min(500, vy));
        cy += vy;
        score = dist / 5;
        if ((frame & 1) == 0) trail[trailN++ % 10] = cy >> 8;
        if (up && (frame & 3) == 0) Sfx.tone(40, 20);
        int px = W / 4, y = cy >> 8;
        int c = (head + (px + scroll) / colW) % COLS;
        int r = Math.max(3, W / 40);
        if (y - r < top[c] || y + r > bot[c]) crash();
        int b = block[c];
        if (b != 0 && y + r > b && y - r < b + Math.max(10, H / 10)) crash();
    }

    private void crash() {
        deadT = 25;
        headline = "CRASHED!";
        Sfx.bad();
    }

    protected void draw(Graphics g) {
        g.setColor(0x0E1A0E);
        g.fillRect(0, 0, W, H);
        int n = W / colW + 2;
        for (int k = 0; k < n; k++) {
            int c = (head + k) % COLS;
            int x = k * colW - scroll;
            g.setColor(0x558B2F);
            g.fillRect(x, 0, colW, top[c]);
            g.fillRect(x, bot[c], colW, H - bot[c]);
            g.setColor(0x8BC34A);
            g.fillRect(x, top[c] - 2, colW, 2);
            g.fillRect(x, bot[c], colW, 2);
            if (block[c] != 0) {
                Gfx.bevel(g, x, block[c], colW * 2, Math.max(10, H / 10), 0x795548);
            }
        }
        int px = W / 4, y = cy >> 8, r = Math.max(3, W / 40);
        g.setColor(0x33691E);
        for (int k = 0; k < Math.min(trailN, 10); k++) g.fillRect(px - 4 - k * 3, trail[(trailN - 1 - k + 100) % 10], 2, 2);
        if (deadT == 0 || (clock & 2) == 0) {
            g.setColor(0xFFC107);
            g.fillRoundRect(px - r * 2, y - r, r * 3, r * 2, r, r);
            g.fillRect(px - r * 4, y - 1, r * 2, 2);
            g.setColor(0x81D4FA);
            g.fillRect(px, y - r + 1, r, r - 1);
            g.setColor(0xECEFF1);
            int blade = (clock & 1) == 0 ? r * 3 : r;
            g.drawLine(px - blade, y - r - 2, px + blade, y - r - 2);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(score + " m", 2, 1, Gfx.TL);
        g.setColor(0xC5E1A5);
        g.drawString("best " + Math.max(best(), score), W - 2, 1, Gfx.TR);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        g.setColor(0x558B2F);
        for (int k = 0; k < w; k += 4) {
            int t = (gamekit.FMath.sin((k * 3 + clock * 4) & 255) * (h / 6) >> 10);
            g.fillRect(x + k, y, 4, h / 4 + t);
            g.fillRect(x + k, y + h * 3 / 4 + t, 4, h / 4 - t);
        }
        int cy2 = y + h / 2 + (gamekit.FMath.sin(clock * 6) * (h / 8) >> 10);
        g.setColor(0xFFC107);
        g.fillRoundRect(x + w / 3 - 6, cy2 - 3, 10, 6, 4, 4);
    }
}
