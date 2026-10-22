package bubbleshot;

import gamekit.FMath;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Bubble Shot: aim a launcher, fire coloured bubbles into an offset grid and
 * pop groups of three or more. Bubbles left hanging fall away. The ceiling
 * drops every few shots.
 */
public class BubbleGame extends Game {
    private static final int COLS = 8, MAXR = 24;
    private static final int[] COL = { 0, 0xEF5350, 0x42A5F5, 0x66BB6A, 0xFFCA28, 0xAB47BC, 0x26C6DA };

    private final int[][] grid = new int[MAXR][COLS];
    private final boolean[][] mark = new boolean[MAXR][COLS];
    private final int[] qr = new int[MAXR * COLS], qc = new int[MAXR * COLS];
    private int rows, d, ox, top, shift, aim, cur, next, shots, colours;
    private boolean flying;
    private int bx, by, vx, vy;
    private int popFx;

    protected String name() { return "Bubble Shot"; }

    protected String[] help() {
        return new String[] {
            "Aim the launcher and fire bubbles into the cluster. Three or more touching bubbles of the same colour pop, and any bubbles left hanging fall for bonus points.",
            "Bubbles bounce off the side walls. Every few shots the ceiling pushes down a new row; if the bubbles reach the launcher line the game ends.",
            "Clear the board to win.",
            "- Controls",
            "4/6: aim  (hold to sweep)",
            "5: fire",
            "0: swap with next bubble",
        };
    }

    protected String[] modes() { return new String[] { "Relaxed", "Classic" }; }

    protected int accent() { return 0x42A5F5; }

    private void layout() {
        int hud = Gfx.SMALL.getHeight() + 2;
        d = Math.max(8, (W * 2) / (COLS * 2 + 1));
        ox = (W - (COLS * d + d / 2)) / 2;
        top = hud;
        rows = Math.min(MAXR, (H - top - d * 2) / rowH());
    }

    private int rowH() { return d * 7 / 8; }

    private int off(int r) { return ((r + shift) & 1) * d / 2; }

    private int cx(int r, int c) { return ox + c * d + d / 2 + off(r); }

    private int cy(int r) { return top + r * rowH() + d / 2; }

    protected void newGame() {
        layout();
        shift = 0;
        colours = mode == 0 ? 4 : 5;
        for (int r = 0; r < MAXR; r++) for (int c = 0; c < COLS; c++) grid[r][c] = 0;
        int fill = Math.max(2, Math.min(5, rows / 2));
        for (int r = 0; r < fill; r++) for (int c = 0; c < COLS; c++) grid[r][c] = 1 + Rnd.nextInt(colours);
        aim = 64 + 128; // straight up (screen y is down)
        shots = 0;
        flying = false;
        cur = pickColour();
        next = pickColour();
    }

    private int pickColour() {
        boolean[] have = new boolean[8];
        int n = 0;
        for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) if (grid[r][c] > 0 && !have[grid[r][c]]) { have[grid[r][c]] = true; n++; }
        if (n == 0) return 1 + Rnd.nextInt(colours);
        int k = Rnd.nextInt(n);
        for (int i = 1; i < 8; i++) if (have[i] && k-- == 0) return i;
        return 1;
    }

    private boolean occupied(int r, int c) {
        return r >= 0 && r < rows && c >= 0 && c < COLS && grid[r][c] > 0;
    }

    protected void update() {
        if (d == 0 || rows == 0) layout();
        if (popFx > 0) popFx--;
        if (!flying) {
            int step = (held & (K_LEFT | K_RIGHT)) != 0 ? 2 : 0;
            if ((pressed & K_LEFT) != 0) step = 1;
            if ((pressed & K_RIGHT) != 0) step = 1;
            if ((held & K_LEFT) != 0 || (pressed & K_LEFT) != 0) aim -= step;
            if ((held & K_RIGHT) != 0 || (pressed & K_RIGHT) != 0) aim += step;
            aim = FMath.clamp(aim, 128 + 10, 256 - 10);
            if (digit(0)) {
                int t = cur;
                cur = next;
                next = t;
                Sfx.click();
            }
            if ((pressed & K_FIRE) != 0) {
                flying = true;
                bx = (W / 2) << 8;
                by = (H - d) << 8;
                int sp = Math.max(3, d / 2);
                vx = FMath.cos(aim) * sp / 4;
                vy = FMath.sin(aim) * sp / 4;
                Sfx.tone(76, 20);
            }
            return;
        }
        for (int s = 0; s < 4 && flying; s++) {
            bx += vx;
            by += vy;
            int minX = (ox + d / 2) << 8, maxX = (ox + COLS * d) << 8;
            if (bx < minX) { bx = minX; vx = -vx; }
            if (bx > maxX) { bx = maxX; vx = -vx; }
            int x = bx >> 8, y = by >> 8;
            boolean hit = y <= top + d / 2;
            if (!hit) {
                int r0 = (y - top) / rowH();
                for (int r = r0 - 1; r <= r0 + 1 && !hit; r++) {
                    if (r < 0 || r >= rows) continue;
                    for (int c = 0; c < COLS; c++) {
                        if (grid[r][c] == 0) continue;
                        int dx = cx(r, c) - x, dy = cy(r) - y;
                        int lim = d * 13 / 16;
                        if (dx * dx + dy * dy < lim * lim) { hit = true; break; }
                    }
                }
            }
            if (hit) land(x, y);
        }
    }

    private void land(int x, int y) {
        flying = false;
        int br = -1, bc = -1, bd = Integer.MAX_VALUE;
        for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) {
            if (grid[r][c] != 0) continue;
            if (r > 0 && !(neighbourFilled(r, c))) continue;
            int dx = cx(r, c) - x, dy = cy(r) - y;
            int dd = dx * dx + dy * dy;
            if (dd < bd) { bd = dd; br = r; bc = c; }
        }
        if (br < 0) {
            lose();
            return;
        }
        grid[br][bc] = cur;
        Sfx.hit();
        int n = flood(br, bc, cur);
        if (n >= 3) {
            for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) if (mark[r][c]) grid[r][c] = 0;
            score += n * 10;
            int dropped = dropFloating();
            score += dropped * 20;
            popFx = 8;
            Sfx.good();
        } else {
            shots++;
            int every = mode == 0 ? 8 : 6;
            if (shots % every == 0) pushRow();
        }
        cur = next;
        next = pickColour();
        boolean any = false;
        for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) if (grid[r][c] > 0) any = true;
        if (!any) {
            score += 1000;
            headline = "BOARD CLEARED!";
            endGame(true);
            return;
        }
        for (int c = 0; c < COLS; c++) if (grid[rows - 1][c] > 0) { lose(); return; }
    }

    private boolean neighbourFilled(int r, int c) {
        int o = ((r + shift) & 1);
        int a = o == 1 ? c : c - 1, b = a + 1;
        return occupied(r - 1, a) || occupied(r - 1, b) || occupied(r, c - 1) || occupied(r, c + 1)
                || occupied(r + 1, a) || occupied(r + 1, b);
    }

    private void lose() {
        headline = "THE CEILING WINS";
        endGame(false);
    }

    private int flood(int r0, int c0, int colour) {
        for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) mark[r][c] = false;
        int head = 0, tail = 0;
        qr[tail] = r0; qc[tail++] = c0;
        mark[r0][c0] = true;
        while (head < tail) {
            int r = qr[head], c = qc[head++];
            int o = ((r + shift) & 1);
            int a = o == 1 ? c : c - 1;
            int[] nr = { r, r, r - 1, r - 1, r + 1, r + 1 };
            int[] nc = { c - 1, c + 1, a, a + 1, a, a + 1 };
            for (int k = 0; k < 6; k++) {
                int rr = nr[k], cc = nc[k];
                if (rr < 0 || rr >= rows || cc < 0 || cc >= COLS || mark[rr][cc]) continue;
                if (colour > 0 ? grid[rr][cc] != colour : grid[rr][cc] == 0) continue;
                mark[rr][cc] = true;
                qr[tail] = rr; qc[tail++] = cc;
            }
        }
        return tail;
    }

    private int dropFloating() {
        // mark everything connected to the ceiling
        boolean[][] keep = new boolean[rows][COLS];
        for (int c = 0; c < COLS; c++) {
            if (grid[0][c] == 0 || keep[0][c]) continue;
            flood(0, c, 0);
            for (int r = 0; r < rows; r++) for (int k = 0; k < COLS; k++) if (mark[r][k]) keep[r][k] = true;
        }
        int n = 0;
        for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) if (grid[r][c] > 0 && !keep[r][c]) { grid[r][c] = 0; n++; }
        return n;
    }

    private void pushRow() {
        for (int r = rows - 1; r > 0; r--) for (int c = 0; c < COLS; c++) grid[r][c] = grid[r - 1][c];
        shift ^= 1;
        for (int c = 0; c < COLS; c++) grid[0][c] = pickColour();
        Sfx.bad();
    }

    private void bubble(Graphics g, int x, int y, int colour, int r) {
        g.setColor(Gfx.shade(COL[colour], -30));
        Gfx.disc(g, x, y, r);
        g.setColor(COL[colour]);
        Gfx.disc(g, x, y, r - 1);
        g.setColor(Gfx.shade(COL[colour], 50));
        g.fillRect(x - r / 2, y - r / 2, Math.max(1, r / 3), Math.max(1, r / 3));
    }

    protected void draw(Graphics g) {
        if (d == 0) layout();
        g.setColor(0x10162A);
        g.fillRect(0, 0, W, H);
        g.setColor(0x1C2541);
        g.fillRect(ox, top, COLS * d + d / 2, H - top);
        int r2 = d / 2;
        for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) if (grid[r][c] > 0) bubble(g, cx(r, c), cy(r), grid[r][c], r2);
        // danger line
        g.setColor(0xFF5252);
        int ly = top + (rows - 1) * rowH();
        for (int x = ox; x < ox + COLS * d; x += 6) g.drawLine(x, ly, x + 2, ly);
        // aim guide
        int sx = W / 2, sy = H - d;
        g.setColor(0x90A4AE);
        for (int k = 2; k < 9; k++) {
            int gx = sx + FMath.cos(aim) * k * d / 2048, gy = sy + FMath.sin(aim) * k * d / 2048;
            g.fillRect(gx, gy, 2, 2);
        }
        if (flying) bubble(g, bx >> 8, by >> 8, cur, r2);
        else bubble(g, sx, sy, cur, r2);
        bubble(g, sx + d * 2, H - d / 2 - 1, next, Math.max(3, r2 - 2));
        g.setColor(0x000000);
        g.fillRect(0, 0, W, top);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(score), 2, 1, Gfx.TL);
        int every = mode == 0 ? 8 : 6;
        g.setColor(0xFFAB91);
        g.drawString("drop in " + (every - shots % every), W - 2, 1, Gfx.TR);
        if (popFx > 0) {
            g.setColor(0xFFFFFF);
            g.drawArc(sx - popFx * 2, sy - popFx * 2, popFx * 4, popFx * 4, 0, 360);
        }
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int r = Math.max(4, Math.min(w / 14, h / 5));
        for (int row = 0; row < 3; row++) for (int k = 0; k < 6; k++) {
            int c = 1 + (k + row * 2 + clock / 15) % 5;
            bubble(g, x + w / 2 - r * 6 + k * r * 2 + (row & 1) * r + r, y + r + row * r * 7 / 4, c, r);
        }
    }
}
