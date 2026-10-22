package pipeflow;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Pipe Flow: rotate pipe tiles to connect the water inlet on the left to
 * the drain on the right. Puzzles are built from a guaranteed path, then
 * every tile is spun randomly.
 * Tile masks: 1 north, 2 east, 4 south, 8 west.
 */
public class PipeGame extends Game {
    private int n, inRow, outRow, cur, level, moves, flowT;
    private int[] m;
    private boolean[] wet;
    private int[] stack;

    protected String name() { return "Pipe Flow"; }

    protected String[] help() {
        return new String[] {
            "Water enters on the left edge and must reach the drain on the right. Rotate the pipe tiles until a connected path links them.",
            "Every puzzle has at least one solution. Tiles already carrying water turn blue as you work.",
            "- Controls",
            "2/4/6/8: move cursor",
            "5: rotate tile clockwise  0: anticlockwise",
        };
    }

    protected String[] modes() { return new String[] { "5x5", "7x7" }; }

    protected int accent() { return 0x29B6F6; }

    protected void newGame() {
        level = 0;
        nextLevel();
    }

    private static int rot(int v) { return ((v << 1) | (v >> 3)) & 15; }

    private void nextLevel() {
        level++;
        n = mode == 0 ? 5 : 7;
        m = new int[n * n];
        wet = new boolean[n * n];
        stack = new int[n * n];
        inRow = Rnd.nextInt(n);
        outRow = Rnd.nextInt(n);
        // random self-avoiding walk from the left column to the right column
        boolean[] used = new boolean[n * n];
        int[] path = new int[n * n];
        int len;
        while (true) {
            for (int i = 0; i < n * n; i++) used[i] = false;
            int x = 0, y = inRow;
            len = 0;
            path[len++] = y * n + x;
            used[y * n + x] = true;
            boolean ok = false;
            for (int steps = 0; steps < n * n * 3; steps++) {
                if (x == n - 1 && y == outRow) { ok = true; break; }
                int d = Rnd.nextInt(4);
                if (Rnd.chance(45)) d = x < n - 1 ? 1 : (y < outRow ? 2 : 0);
                int nx = x + (d == 1 ? 1 : (d == 3 ? -1 : 0)), ny = y + (d == 2 ? 1 : (d == 0 ? -1 : 0));
                if (nx < 0 || ny < 0 || nx >= n || ny >= n || used[ny * n + nx]) continue;
                x = nx;
                y = ny;
                used[y * n + x] = true;
                path[len++] = y * n + x;
            }
            if (ok) break;
        }
        for (int k = 0; k < len; k++) {
            int i = path[k], mask = 0;
            int prev = k == 0 ? -1 : path[k - 1], next = k == len - 1 ? -2 : path[k + 1];
            if (k == 0) mask |= 8;
            if (k == len - 1) mask |= 2;
            if (prev >= 0) mask |= dirTo(i, prev);
            if (next >= 0) mask |= dirTo(i, next);
            m[i] = mask;
        }
        for (int i = 0; i < n * n; i++) {
            if (m[i] == 0) {
                int r = Rnd.nextInt(10);
                m[i] = r < 4 ? 5 : (r < 8 ? 3 : (r < 9 ? 7 : 15));
            }
            int spins = Rnd.nextInt(4);
            for (int k = 0; k < spins; k++) m[i] = rot(m[i]);
        }
        moves = 0;
        flowT = 0;
        cur = inRow * n;
        flood();
    }

    private int dirTo(int a, int b) {
        int ax = a % n, ay = a / n, bx = b % n, by = b / n;
        if (bx > ax) return 2;
        if (bx < ax) return 8;
        if (by > ay) return 4;
        return 1;
    }

    /** Mark tiles connected to the inlet; returns true if the drain is reached. */
    private boolean flood() {
        for (int i = 0; i < n * n; i++) wet[i] = false;
        int start = inRow * n;
        if ((m[start] & 8) == 0) return false;
        int sp = 0;
        stack[sp++] = start;
        wet[start] = true;
        boolean done = false;
        while (sp > 0) {
            int i = stack[--sp];
            int x = i % n, y = i / n, v = m[i];
            if (x == n - 1 && y == outRow && (v & 2) != 0) done = true;
            for (int d = 0; d < 4; d++) {
                int bit = 1 << d;
                if ((v & bit) == 0) continue;
                int nx = x + (d == 1 ? 1 : (d == 3 ? -1 : 0)), ny = y + (d == 2 ? 1 : (d == 0 ? -1 : 0));
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                int j = ny * n + nx;
                int back = 1 << ((d + 2) & 3);
                if (wet[j] || (m[j] & back) == 0) continue;
                wet[j] = true;
                stack[sp++] = j;
            }
        }
        return done;
    }

    protected void update() {
        if (flowT > 0) {
            if (++flowT > 40) {
                if (level >= 15) {
                    headline = "MASTER PLUMBER";
                    endGame(true);
                    return;
                }
                nextLevel();
            }
            return;
        }
        int x = cur % n, y = cur / n;
        if ((pressed & K_LEFT) != 0) x = (x + n - 1) % n;
        if ((pressed & K_RIGHT) != 0) x = (x + 1) % n;
        if ((pressed & K_UP) != 0) y = (y + n - 1) % n;
        if ((pressed & K_DOWN) != 0) y = (y + 1) % n;
        cur = y * n + x;
        if ((pressed & K_FIRE) != 0 || digit(0)) {
            m[cur] = (pressed & K_FIRE) != 0 ? rot(m[cur]) : rot(rot(rot(m[cur])));
            moves++;
            Sfx.click();
            if (flood()) {
                score += 100 + Math.max(0, 60 - moves) * 3 + level * 10;
                flowT = 1;
                Sfx.win();
            }
        }
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int s = Math.max(10, Math.min(W - 16, H - hud * 2) / n);
        int ox = (W - s * n) / 2, oy = hud + (H - hud * 2 - s * n) / 2;
        g.setColor(0x1C2833);
        g.fillRect(0, 0, W, H);
        g.setColor(0x29B6F6);
        g.fillRect(0, oy + inRow * s + s / 2 - 2, ox, 4);
        g.setColor(0x546E7A);
        g.fillRect(ox + n * s, oy + outRow * s + s / 2 - 2, W - ox - n * s, 4);
        int pipe = Math.max(3, s / 4);
        for (int i = 0; i < n * n; i++) {
            int x = ox + (i % n) * s, y = oy + (i / n) * s;
            g.setColor(0x2E4053);
            g.fillRect(x + 1, y + 1, s - 2, s - 2);
            int c = wet[i] ? (flowT > 0 && ((clock + i) & 2) == 0 ? 0x81D4FA : 0x29B6F6) : 0x9E9E9E;
            g.setColor(c);
            int cx = x + s / 2, cy = y + s / 2;
            g.fillRect(cx - pipe / 2, cy - pipe / 2, pipe, pipe);
            if ((m[i] & 1) != 0) g.fillRect(cx - pipe / 2, y, pipe, s / 2);
            if ((m[i] & 4) != 0) g.fillRect(cx - pipe / 2, cy, pipe, s / 2);
            if ((m[i] & 8) != 0) g.fillRect(x, cy - pipe / 2, s / 2, pipe);
            if ((m[i] & 2) != 0) g.fillRect(cx, cy - pipe / 2, s / 2, pipe);
        }
        int cx = ox + (cur % n) * s, cy = oy + (cur / n) * s;
        g.setColor((clock & 4) == 0 ? 0xFFEB3B : 0xFFFFFF);
        g.drawRect(cx, cy, s - 1, s - 1);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("Level " + level, 2, 1, Gfx.TL);
        g.setColor(0x81D4FA);
        g.drawString(moves + " turns", W - 2, 1, Gfx.TR);
        if (flowT > 0) Gfx.shadowText(g, "IT FLOWS!", W / 2, H - hud, Gfx.TC, Gfx.SMALL_B, 0x81D4FA, 0x000000);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(8, h / 2);
        int k = w / s;
        int fill = (clock / 4) % (k + 4);
        for (int i = 0; i < k; i++) {
            g.setColor(i < fill ? 0x29B6F6 : 0x9E9E9E);
            g.fillRect(x + i * s, y + h / 2 - 3, s, 6);
        }
    }
}
