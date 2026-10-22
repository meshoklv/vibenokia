package slidefifteen;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Slide Fifteen: the sliding tile puzzle in 3x3, 4x4 and 5x5. */
public class SlideGame extends Game {
    private int n, blank, moves;
    private int[] t;
    private int animFrom = -1, animTo, anim;

    protected String name() { return "Slide Fifteen"; }

    protected String[] help() {
        return new String[] {
            "Slide the numbered tiles back into order, 1 in the top-left corner and the gap in the bottom-right.",
            "The direction keys move a tile into the gap: press 2 (up) to slide the tile below the gap upwards, and so on.",
            "Every shuffle is made of legal moves, so every puzzle can be solved. Fewer moves is better.",
            "- Controls",
            "2/4/6/8: slide a tile",
        };
    }

    protected String[] modes() { return new String[] { "4x4 Fifteen", "3x3 Eight", "5x5 Twenty-four" }; }

    protected boolean lowerIsBetter() { return true; }

    protected String formatScore(int s) { return s + " moves"; }

    protected int accent() { return 0x4C8DFF; }

    protected void newGame() {
        n = mode == 0 ? 4 : (mode == 1 ? 3 : 5);
        t = new int[n * n];
        for (int i = 0; i < n * n - 1; i++) t[i] = i + 1;
        t[n * n - 1] = 0;
        blank = n * n - 1;
        int last = -1;
        for (int k = 0; k < n * n * 40; k++) {
            int d = Rnd.nextInt(4);
            if (d == (last ^ 1)) continue; // don't immediately undo
            if (slide(d, false)) last = d;
        }
        if (solved()) slide(0, false);
        moves = 0;
        animFrom = -1;
    }

    /** Move a tile into the blank. d: 0 up, 1 down, 2 left, 3 right (tile movement). */
    private boolean slide(int d, boolean user) {
        int bx = blank % n, by = blank / n;
        int sx = bx, sy = by;
        if (d == 0) sy = by + 1;
        else if (d == 1) sy = by - 1;
        else if (d == 2) sx = bx + 1;
        else sx = bx - 1;
        if (sx < 0 || sy < 0 || sx >= n || sy >= n) return false;
        int src = sy * n + sx;
        t[blank] = t[src];
        t[src] = 0;
        if (user) {
            animFrom = src;
            animTo = blank;
            anim = 3;
        }
        blank = src;
        return true;
    }

    private boolean solved() {
        for (int i = 0; i < n * n - 1; i++) if (t[i] != i + 1) return false;
        return true;
    }

    protected void update() {
        if (anim > 0) anim--;
        int d = -1;
        if ((pressed & K_UP) != 0) d = 0;
        else if ((pressed & K_DOWN) != 0) d = 1;
        else if ((pressed & K_LEFT) != 0) d = 2;
        else if ((pressed & K_RIGHT) != 0) d = 3;
        if (d >= 0) {
            if (slide(d, true)) {
                moves++;
                score = moves;
                Sfx.tone(64 + (moves % 5), 15);
                if (solved()) {
                    score = moves;
                    endGame(true);
                }
            } else {
                Sfx.tone(40, 15);
            }
        }
    }

    private void tile(Graphics g, int v, int x, int y, int s, boolean home) {
        int row = (v - 1) / n;
        int c = Gfx.mix(0x4C8DFF, 0xB65CF0, row * 256 / n);
        Gfx.bevel(g, x + 1, y + 1, s - 2, s - 2, home ? Gfx.shade(c, 20) : Gfx.shade(c, -25));
        Font f = s >= 34 ? Gfx.LARGE : (s >= 18 ? Gfx.MEDIUM : Gfx.SMALL_B);
        g.setFont(f);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(v), x + s / 2 + 1, y + (s - f.getBaselinePosition()) / 2, Gfx.TC);
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int size = Math.min(W - 8, H - hud * 3);
        int s = size / n;
        size = s * n;
        int ox = (W - size) / 2, oy = hud * 2 + (H - hud * 3 - size) / 2;
        g.setColor(0x0E1430);
        g.fillRect(0, 0, W, H);
        g.setColor(0x050814);
        g.fillRect(ox - 3, oy - 3, size + 6, size + 6);
        for (int i = 0; i < n * n; i++) {
            if (t[i] == 0) continue;
            int x = ox + (i % n) * s, y = oy + (i / n) * s;
            if (i == animTo && anim > 0) {
                int fx = ox + (animFrom % n) * s, fy = oy + (animFrom / n) * s;
                x = x + (fx - x) * anim / 4;
                y = y + (fy - y) * anim / 4;
            }
            tile(g, t[i], x, y, s, t[i] == i + 1);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("Moves " + moves, 2, 1, Gfx.TL);
        g.setColor(0x9FB2E6);
        g.drawString(Gfx.time(frame, tickMs), W - 2, 1, Gfx.TR);
        if (best() > 0) {
            g.setFont(Gfx.SMALL);
            g.drawString("Best " + best(), W / 2, hud, Gfx.TC);
        }
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(8, Math.min(h, w) / 3 - 1);
        int ox = x + (w - s * 3) / 2, oy = y + (h - s * 3) / 2;
        n = 3;
        int gap = (clock / 15) % 9;
        int v = 1;
        for (int i = 0; i < 9; i++) {
            if (i == gap) continue;
            tile(g, v, ox + (i % 3) * s, oy + (i / 3) * s, s, true);
            v++;
        }
    }
}
