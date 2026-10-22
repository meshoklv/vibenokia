package switchgrid;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Switch Grid: pressing a light toggles it and its neighbours. Turn every
 * light off. On the 3x3 board the keypad IS the grid: keys 1-9.
 */
public class SwitchGame extends Game {
    private int n, level, presses, par, cur, flashT;
    private boolean wrap;
    private boolean[] on;

    protected String name() { return "Switch Grid"; }

    protected String[] help() {
        return new String[] {
            "Every switch you press flips itself and the switches directly above, below, left and right of it. Turn all the lights off.",
            "Keypad 3x3: keys 1-9 are the switches. Classic 5x5: move the cursor and press 5. Wrap 5x5: neighbours wrap around the edges.",
            "Each puzzle is made by pressing random switches, so it can always be solved. Solving within par earns a bonus. Puzzles get harder as you go.",
            "- Controls",
            "1-9: press switch (3x3)",
            "2/4/6/8 + 5: cursor (5x5)",
        };
    }

    protected String[] modes() { return new String[] { "Keypad 3x3", "Classic 5x5", "Wrap 5x5" }; }

    protected int accent() { return 0xFFEB3B; }

    protected void newGame() {
        n = mode == 0 ? 3 : 5;
        wrap = mode == 2;
        level = 0;
        nextPuzzle();
    }

    private void nextPuzzle() {
        level++;
        on = new boolean[n * n];
        par = Math.min(n * n - 1, 2 + level + (n == 5 ? 2 : 0));
        boolean[] used = new boolean[n * n];
        int k = 0;
        while (k < par) {
            int i = Rnd.nextInt(n * n);
            if (used[i]) continue;
            used[i] = true;
            press(i);
            k++;
        }
        boolean any = false;
        for (int i = 0; i < n * n; i++) any |= on[i];
        if (!any) press(n * n / 2);
        presses = 0;
        cur = n * n / 2;
    }

    private void flip(int x, int y) {
        if (wrap) {
            x = (x + n) % n;
            y = (y + n) % n;
        } else if (x < 0 || y < 0 || x >= n || y >= n) return;
        on[y * n + x] = !on[y * n + x];
    }

    private void press(int i) {
        int x = i % n, y = i / n;
        flip(x, y);
        flip(x - 1, y);
        flip(x + 1, y);
        flip(x, y - 1);
        flip(x, y + 1);
    }

    protected void update() {
        if (flashT > 0) {
            if (--flashT == 0) nextPuzzle();
            return;
        }
        int target = -1;
        if (n == 3) {
            int d = digitPressed();
            if (d >= 1) target = d - 1;
        } else {
            int x = cur % n, y = cur / n;
            if ((pressed & K_LEFT) != 0) x = (x + n - 1) % n;
            if ((pressed & K_RIGHT) != 0) x = (x + 1) % n;
            if ((pressed & K_UP) != 0) y = (y + n - 1) % n;
            if ((pressed & K_DOWN) != 0) y = (y + 1) % n;
            cur = y * n + x;
            if ((pressed & K_FIRE) != 0) target = cur;
        }
        if (target < 0) return;
        cur = target;
        press(target);
        presses++;
        Sfx.tone(60 + (target % 7) * 3, 25);
        for (int i = 0; i < n * n; i++) if (on[i]) return;
        int bonus = presses <= par ? 50 : 0;
        score += 100 + level * 10 + bonus;
        flashT = 30;
        Sfx.win();
        if (level >= 20) {
            headline = "ALL DARK!";
            endGame(true);
        }
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        g.setColor(0x0A0A12);
        g.fillRect(0, 0, W, H);
        int size = Math.min(W - 8, H - hud * 3);
        int s = size / n;
        int ox = (W - s * n) / 2, oy = hud * 2 + (H - hud * 3 - s * n) / 2;
        for (int i = 0; i < n * n; i++) {
            int x = ox + (i % n) * s, y = oy + (i / n) * s;
            boolean lit = on[i] || (flashT > 0 && (flashT & 4) != 0);
            g.setColor(0x1E1E2A);
            g.fillRoundRect(x + 2, y + 2, s - 4, s - 4, s / 4, s / 4);
            if (lit) {
                g.setColor(0xFFF176);
                g.fillRoundRect(x + 3, y + 3, s - 6, s - 6, s / 4, s / 4);
                g.setColor(0xFFFFFF);
                g.fillRoundRect(x + s / 3, y + s / 3, s / 3, s / 4, 4, 4);
            } else {
                g.setColor(0x33334A);
                g.drawRoundRect(x + 3, y + 3, s - 7, s - 7, s / 4, s / 4);
            }
            if (n == 3) {
                g.setFont(s >= 30 ? Gfx.MEDIUM : Gfx.SMALL_B);
                g.setColor(lit ? 0x5D4037 : 0x6A6A88);
                g.drawString(String.valueOf(i + 1), x + 6, y + 5, Gfx.TL);
            } else if (i == cur && flashT == 0) {
                g.setColor(0x40C4FF);
                g.drawRect(x + 1, y + 1, s - 3, s - 3);
            }
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("Level " + level, 2, 1, Gfx.TL);
        g.setColor(presses <= par ? 0xA5D6A7 : 0xFF8A80);
        g.drawString(presses + "/" + par, W - 2, 1, Gfx.TR);
        Gfx.text(g, String.valueOf(score), W / 2, hud, Gfx.TC, Gfx.SMALL, 0xFFEB3B);
        if (flashT > 0) Gfx.shadowText(g, "LIGHTS OUT!", W / 2, H - hud, Gfx.TC, Gfx.SMALL_B, 0xFFEB3B, 0x000000);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(8, Math.min(h, w) / 3 - 2);
        int ox = x + (w - s * 3) / 2, oy = y + (h - s * 3) / 2;
        int k = (clock / 10) % 9;
        for (int i = 0; i < 9; i++) {
            int dx = Math.abs(i % 3 - k % 3), dy = Math.abs(i / 3 - k / 3);
            boolean lit = dx + dy <= 1;
            g.setColor(lit ? 0xFFF176 : 0x1E1E2A);
            g.fillRoundRect(ox + (i % 3) * s + 1, oy + (i / 3) * s + 1, s - 2, s - 2, 6, 6);
        }
    }
}
