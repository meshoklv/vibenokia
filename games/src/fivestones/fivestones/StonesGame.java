package fivestones;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Five Stones: get five in a row on an 11x11 board, against a pattern-scoring CPU. */
public class StonesGame extends Game {
    private static final int N = 11;
    private static final int[] DX = { 1, 0, 1, 1 }, DY = { 0, 1, 1, -1 };

    private final int[] b = new int[N * N];
    private int cur = N * N / 2, turn, wait, moves, last = -1, winStart = -1, winDir;

    protected String name() { return "Five Stones"; }

    protected String[] help() {
        return new String[] {
            "Take turns placing stones on the intersections. The first to make an unbroken line of five - across, down or diagonally - wins.",
            "You play black and move first. Watch out for the CPU's open threes and fours: a row of four with both ends open can't be stopped.",
            "- Controls",
            "2/4/6/8, 1/3/7/9: move cursor",
            "5: place stone",
        };
    }

    protected String[] modes() { return new String[] { "vs CPU Easy", "vs CPU Hard", "2 Players" }; }

    protected boolean hasScore() { return false; }

    protected String footer() { return "Won " + saved[0] + "  Lost " + saved[1]; }

    protected int accent() { return 0xD4A373; }

    private boolean cpu() { return mode < 2; }

    protected void newGame() {
        for (int i = 0; i < N * N; i++) b[i] = 0;
        turn = 1;
        moves = 0;
        last = -1;
        winStart = -1;
        cur = N * N / 2;
    }

    private int line(int x, int y, int dx, int dy, int p) {
        int n = 0;
        x += dx;
        y += dy;
        while (x >= 0 && y >= 0 && x < N && y < N && b[y * N + x] == p) {
            n++;
            x += dx;
            y += dy;
        }
        return n;
    }

    private boolean openEnd(int x, int y, int dx, int dy, int p) {
        while (x >= 0 && y >= 0 && x < N && y < N && b[y * N + x] == p) {
            x += dx;
            y += dy;
        }
        return x >= 0 && y >= 0 && x < N && y < N && b[y * N + x] == 0;
    }

    private boolean fives(int i) {
        int x = i % N, y = i / N, p = b[i];
        for (int d = 0; d < 4; d++) {
            int back = line(x, y, -DX[d], -DY[d], p);
            if (1 + back + line(x, y, DX[d], DY[d], p) >= 5) {
                winStart = (y - DY[d] * back) * N + (x - DX[d] * back);
                winDir = d;
                return true;
            }
        }
        return false;
    }

    /** Pattern value of placing p at empty cell i. */
    private int value(int i, int p) {
        int x = i % N, y = i / N, total = 0;
        b[i] = p;
        for (int d = 0; d < 4; d++) {
            int a = line(x, y, DX[d], DY[d], p), c = line(x, y, -DX[d], -DY[d], p);
            int len = 1 + a + c;
            int open = (openEnd(x + DX[d], y + DY[d], DX[d], DY[d], p) ? 1 : 0) + (openEnd(x - DX[d], y - DY[d], -DX[d], -DY[d], p) ? 1 : 0);
            if (len >= 5) total += 100000;
            else if (len == 4) total += open == 2 ? 20000 : (open == 1 ? 2000 : 0);
            else if (len == 3) total += open == 2 ? 1500 : (open == 1 ? 150 : 0);
            else if (len == 2) total += open == 2 ? 120 : (open == 1 ? 15 : 0);
            else total += open;
        }
        b[i] = 0;
        return total;
    }

    private int cpuMove() {
        int best = -1, bestI = N * N / 2;
        for (int i = 0; i < N * N; i++) {
            if (b[i] != 0 || !near(i)) continue;
            int attack = value(i, 2), defend = value(i, 1);
            int v = mode == 0 ? attack + defend * 6 / 10 + Rnd.nextInt(200) : attack + defend * 9 / 10 + Rnd.nextInt(10);
            int x = i % N, y = i / N;
            v += 10 - Math.abs(x - N / 2) - Math.abs(y - N / 2);
            if (v > best) {
                best = v;
                bestI = i;
            }
        }
        return bestI;
    }

    private boolean near(int i) {
        if (moves == 0) return true;
        int x = i % N, y = i / N;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            int nx = x + dx, ny = y + dy;
            if (nx >= 0 && ny >= 0 && nx < N && ny < N && b[ny * N + nx] != 0) return true;
        }
        return false;
    }

    private void place(int i) {
        if (b[i] != 0) {
            Sfx.bad();
            return;
        }
        b[i] = turn;
        last = i;
        moves++;
        Sfx.tone(turn == 1 ? 60 : 67, 25);
        if (fives(i)) {
            boolean human = !cpu() || turn == 1;
            if (cpu()) saved[human ? 0 : 1]++;
            headline = cpu() ? (human ? "FIVE IN A ROW!" : "CPU WINS") : (turn == 1 ? "BLACK WINS" : "WHITE WINS");
            endGame(human);
            return;
        }
        if (moves == N * N) {
            headline = "DRAW";
            endGame(false);
            return;
        }
        turn = 3 - turn;
    }

    protected void update() {
        if (cpu() && turn == 2) {
            if (++wait < 8) return;
            wait = 0;
            place(cpuMove());
            return;
        }
        int x = cur % N, y = cur / N, dx = 0, dy = 0;
        if ((pressed & K_LEFT) != 0) dx = -1;
        if ((pressed & K_RIGHT) != 0) dx = 1;
        if ((pressed & K_UP) != 0) dy = -1;
        if ((pressed & K_DOWN) != 0) dy = 1;
        if (digit(1)) { dx = -1; dy = -1; }
        if (digit(3)) { dx = 1; dy = -1; }
        if (digit(7)) { dx = -1; dy = 1; }
        if (digit(9)) { dx = 1; dy = 1; }
        cur = ((y + dy + N) % N) * N + (x + dx + N) % N;
        if ((pressed & K_FIRE) != 0) place(cur);
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int s = Math.max(7, Math.min(W - 4, H - hud * 2) / N);
        int ox = (W - s * N) / 2 + s / 2, oy = hud + (H - hud * 2 - s * N) / 2 + s / 2;
        g.setColor(0x2B1E12);
        g.fillRect(0, 0, W, H);
        g.setColor(0xD4A373);
        g.fillRect(ox - s / 2, oy - s / 2, s * N, s * N);
        g.setColor(0x7F5539);
        for (int k = 0; k < N; k++) {
            g.drawLine(ox, oy + k * s, ox + (N - 1) * s, oy + k * s);
            g.drawLine(ox + k * s, oy, ox + k * s, oy + (N - 1) * s);
        }
        g.fillRect(ox + (N / 2) * s - 1, oy + (N / 2) * s - 1, 3, 3);
        for (int i = 0; i < N * N; i++) {
            if (b[i] == 0) continue;
            int x = ox + (i % N) * s, y = oy + (i / N) * s, r = s / 2 - 1;
            g.setColor(b[i] == 1 ? 0x111111 : 0xF5F5F5);
            Gfx.disc(g, x, y, r);
            g.setColor(b[i] == 1 ? 0x555555 : 0xBDBDBD);
            Gfx.disc(g, x - r / 3, y - r / 3, Math.max(1, r / 3));
            if (i == last) {
                g.setColor(0xE53935);
                g.fillRect(x - 1, y - 1, 2, 2);
            }
        }
        if (winStart >= 0) {
            g.setColor(0xE53935);
            int x0 = ox + (winStart % N) * s, y0 = oy + (winStart / N) * s;
            g.drawLine(x0, y0, x0 + DX[winDir] * s * 4, y0 + DY[winDir] * s * 4);
            g.drawLine(x0 + 1, y0, x0 + DX[winDir] * s * 4 + 1, y0 + DY[winDir] * s * 4);
        }
        if (state == PLAY && !(cpu() && turn == 2)) {
            g.setColor((clock & 4) == 0 ? 0x1E88E5 : 0x64B5F6);
            int x = ox + (cur % N) * s, y = oy + (cur / N) * s;
            g.drawRect(x - s / 2, y - s / 2, s - 1, s - 1);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(turn == 1 ? 0xFFFFFF : 0xD4A373);
        String t = cpu() ? (turn == 1 ? "Your move (black)" : "CPU thinking...") : (turn == 1 ? "Black to play" : "White to play");
        if (state == PLAY) g.drawString(t, W / 2, 1, Gfx.TC);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(8, Math.min(h / 2, w / 7));
        int ox = x + (w - s * 6) / 2, oy = y + h / 2;
        g.setColor(0xD4A373);
        g.fillRect(ox - s / 2, oy - s, s * 6, s * 2);
        g.setColor(0x7F5539);
        g.drawLine(ox - s / 2, oy, ox + s * 5 + s / 2, oy);
        int n = (clock / 10) % 7;
        for (int k = 0; k < Math.min(n, 5); k++) {
            g.setColor(0x111111);
            Gfx.disc(g, ox + k * s + s / 2, oy, s / 2 - 1);
        }
    }
}
