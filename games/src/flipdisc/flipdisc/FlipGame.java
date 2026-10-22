package flipdisc;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Flip Disc: outflank and flip your opponent's discs. vs CPU (3 levels) or 2 players. */
public class FlipGame extends Game {
    private static final int[] DX = { -1, 0, 1, -1, 1, -1, 0, 1 }, DY = { -1, -1, -1, 0, 0, 1, 1, 1 };
    private static final int[] WEIGHT = {
        100, -20, 10, 5, 5, 10, -20, 100, -20, -40, -2, -2, -2, -2, -40, -20, 10, -2, 1, 1, 1, 1, -2, 10,
        5, -2, 1, 0, 0, 1, -2, 5, 5, -2, 1, 0, 0, 1, -2, 5, 10, -2, 1, 1, 1, 1, -2, 10,
        -20, -40, -2, -2, -2, -2, -40, -20, 100, -20, 10, 5, 5, 10, -20, 100 };

    private final int[] b = new int[64];
    private final int[][] stack = new int[6][64];
    private final int[] flipAnim = new int[64];
    private int turn, cur = 19, passes, wait, last = -1;
    private String note = "";

    protected String name() { return "Flip Disc"; }

    protected String[] help() {
        return new String[] {
            "Place a disc so that it traps a straight line of your opponent's discs between it and another of yours; all trapped discs flip to your colour.",
            "You must flip at least one disc. If you can't, your turn passes. The game ends when neither player can move; most discs wins.",
            "Legal moves are marked with dots. Corners can never be flipped back - fight for them!",
            "- Controls",
            "2/4/6/8, 1/3/7/9: move cursor",
            "5: place disc",
        };
    }

    protected String[] modes() { return new String[] { "vs CPU Easy", "vs CPU Normal", "vs CPU Hard", "2 Players" }; }

    protected boolean hasScore() { return mode < 3; }

    protected String formatScore(int s) { return s + " discs"; }

    protected int accent() { return 0x2E7D32; }

    private boolean cpu() { return mode < 3; }

    protected void newGame() {
        for (int i = 0; i < 64; i++) {
            b[i] = 0;
            flipAnim[i] = 0;
        }
        b[27] = b[36] = 2;
        b[28] = b[35] = 1;
        turn = 1;
        passes = 0;
        cur = 19;
        last = -1;
        note = "";
    }

    private int flips(int[] bd, int pos, int p, boolean apply) {
        if (bd[pos] != 0) return 0;
        int x0 = pos % 8, y0 = pos / 8, total = 0;
        for (int d = 0; d < 8; d++) {
            int x = x0 + DX[d], y = y0 + DY[d], n = 0;
            while (x >= 0 && y >= 0 && x < 8 && y < 8 && bd[y * 8 + x] == 3 - p) {
                x += DX[d];
                y += DY[d];
                n++;
            }
            if (n > 0 && x >= 0 && y >= 0 && x < 8 && y < 8 && bd[y * 8 + x] == p) {
                total += n;
                if (apply) {
                    for (int k = 1; k <= n; k++) {
                        int i = (y0 + DY[d] * k) * 8 + x0 + DX[d] * k;
                        bd[i] = p;
                        if (bd == b) flipAnim[i] = 6;
                    }
                }
            }
        }
        if (apply && total > 0) bd[pos] = p;
        return total;
    }

    private boolean hasMove(int[] bd, int p) {
        for (int i = 0; i < 64; i++) if (bd[i] == 0 && flips(bd, i, p, false) > 0) return true;
        return false;
    }

    private int evaluate(int[] bd, int p) {
        int s = 0, mob = 0;
        for (int i = 0; i < 64; i++) {
            if (bd[i] == p) s += WEIGHT[i];
            else if (bd[i] == 3 - p) s -= WEIGHT[i];
            else {
                if (flips(bd, i, p, false) > 0) mob++;
                if (flips(bd, i, 3 - p, false) > 0) mob--;
            }
        }
        return s + mob * 4;
    }

    private int search(int[] bd, int p, int depth, int alpha, int beta, int ply) {
        if (depth == 0) return evaluate(bd, p);
        int[] nb = stack[ply];
        boolean any = false;
        for (int i = 0; i < 64; i++) {
            if (bd[i] != 0 || flips(bd, i, p, false) == 0) continue;
            any = true;
            System.arraycopy(bd, 0, nb, 0, 64);
            flips(nb, i, p, true);
            int v = -search(nb, 3 - p, depth - 1, -beta, -alpha, ply + 1);
            if (v > alpha) alpha = v;
            if (alpha >= beta) break;
        }
        if (!any) {
            if (!hasMove(bd, 3 - p)) {
                int diff = 0;
                for (int i = 0; i < 64; i++) diff += bd[i] == p ? 1 : (bd[i] == 3 - p ? -1 : 0);
                return diff * 1000;
            }
            return -search(bd, 3 - p, depth - 1, -beta, -alpha, ply + 1);
        }
        return alpha;
    }

    private int cpuMove() {
        int depth = mode == 0 ? 1 : (mode == 1 ? 2 : 3); // kept shallow for handset CPUs
        int best = -1000000, bestI = -1;
        int[] nb = stack[0];
        for (int i = 0; i < 64; i++) {
            if (b[i] != 0 || flips(b, i, 2, false) == 0) continue;
            System.arraycopy(b, 0, nb, 0, 64);
            flips(nb, i, 2, true);
            int v = -search(nb, 1, depth - 1, -1000000, 1000000, 1);
            if (mode == 0) v += Rnd.range(-30, 30);
            if (v > best) {
                best = v;
                bestI = i;
            }
        }
        return bestI;
    }

    private void play(int pos) {
        flips(b, pos, turn, true);
        last = pos;
        Sfx.tone(turn == 1 ? 67 : 60, 30);
        nextTurn();
    }

    private void nextTurn() {
        turn = 3 - turn;
        if (!hasMove(b, turn)) {
            if (!hasMove(b, 3 - turn)) {
                finish();
                return;
            }
            note = (turn == 1 ? (cpu() ? "You pass" : "Black passes") : (cpu() ? "CPU passes" : "White passes"));
            turn = 3 - turn;
        } else {
            note = "";
        }
    }

    private int count(int p) {
        int n = 0;
        for (int i = 0; i < 64; i++) if (b[i] == p) n++;
        return n;
    }

    private void finish() {
        int bl = count(1), wh = count(2);
        score = bl;
        if (bl == wh) {
            headline = "DRAW " + bl + "-" + wh;
            endGame(false);
        } else if (cpu()) {
            headline = (bl > wh ? "YOU WIN " : "CPU WINS ") + bl + "-" + wh;
            endGame(bl > wh);
        } else {
            headline = (bl > wh ? "BLACK " : "WHITE ") + Math.max(bl, wh) + "-" + Math.min(bl, wh);
            endGame(true);
        }
    }

    protected void update() {
        for (int i = 0; i < 64; i++) if (flipAnim[i] > 0) flipAnim[i]--;
        if (cpu() && turn == 2) {
            if (++wait < 10) return;
            wait = 0;
            int m = cpuMove();
            if (m >= 0) play(m);
            else nextTurn();
            return;
        }
        int x = cur % 8, y = cur / 8, dx = 0, dy = 0;
        if ((pressed & K_LEFT) != 0) dx = -1;
        if ((pressed & K_RIGHT) != 0) dx = 1;
        if ((pressed & K_UP) != 0) dy = -1;
        if ((pressed & K_DOWN) != 0) dy = 1;
        if (digit(1)) { dx = -1; dy = -1; }
        if (digit(3)) { dx = 1; dy = -1; }
        if (digit(7)) { dx = -1; dy = 1; }
        if (digit(9)) { dx = 1; dy = 1; }
        cur = ((y + dy + 8) & 7) * 8 + ((x + dx + 8) & 7);
        if ((pressed & K_FIRE) != 0) {
            if (flips(b, cur, turn, false) > 0) play(cur);
            else Sfx.bad();
        }
    }

    private void disc(Graphics g, int p, int cx, int cy, int r, int anim) {
        int rx = anim > 0 ? Math.max(1, r * Math.abs(anim - 3) / 3) : r;
        g.setColor(p == 1 ? 0x1A1A1A : 0xF5F5F5);
        g.fillArc(cx - rx, cy - r, rx * 2, r * 2, 0, 360);
        g.setColor(p == 1 ? 0x555555 : 0xBDBDBD);
        g.drawArc(cx - rx, cy - r, rx * 2, r * 2, 0, 360);
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int s = Math.max(8, Math.min(W - 4, H - hud * 2 - 2) / 8);
        int ox = (W - s * 8) / 2, oy = hud + (H - hud * 2 - s * 8) / 2;
        g.setColor(0x1B2A1B);
        g.fillRect(0, 0, W, H);
        g.setColor(0x2E7D32);
        g.fillRect(ox, oy, s * 8, s * 8);
        g.setColor(0x1B5E20);
        for (int k = 0; k <= 8; k++) {
            g.drawLine(ox + k * s, oy, ox + k * s, oy + s * 8);
            g.drawLine(ox, oy + k * s, ox + s * 8, oy + k * s);
        }
        boolean human = !(cpu() && turn == 2) && state == PLAY;
        for (int i = 0; i < 64; i++) {
            int cx = ox + (i % 8) * s + s / 2, cy = oy + (i / 8) * s + s / 2;
            if (b[i] != 0) disc(g, b[i], cx, cy, s / 2 - 2, flipAnim[i]);
            else if (human && flips(b, i, turn, false) > 0) {
                g.setColor(turn == 1 ? 0x103010 : 0x8BC34A);
                g.fillRect(cx - 1, cy - 1, 3, 3);
            }
            if (i == last) {
                g.setColor(0xFF5252);
                g.fillRect(cx - 1, cy - 1, 2, 2);
            }
        }
        if (human) {
            g.setColor((clock & 4) == 0 ? 0xFFEB3B : 0xFFFFFF);
            g.drawRect(ox + (cur % 8) * s, oy + (cur / 8) * s, s, s);
        }
        int bl = count(1), wh = count(2);
        disc(g, 1, 8, hud / 2, hud / 2 - 1, 0);
        disc(g, 2, W - 8, hud / 2, hud / 2 - 1, 0);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(bl), 16, 1, Gfx.TL);
        g.drawString(String.valueOf(wh), W - 16, 1, Gfx.TR);
        String t = note.length() > 0 ? note : (turn == 1 ? (cpu() ? "Your move" : "Black") : (cpu() ? "CPU..." : "White"));
        g.setColor(0xA5D6A7);
        g.drawString(t, W / 2, 1, Gfx.TC);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(8, Math.min(h / 2, w / 6));
        int ox = x + (w - s * 4) / 2, oy = y + (h - s * 2) / 2;
        g.setColor(0x2E7D32);
        g.fillRect(ox, oy, s * 4, s * 2);
        for (int i = 0; i < 8; i++) {
            int phase = (clock / 6 + i) % 16;
            int p = phase < 8 ? 1 : 2;
            disc(g, p, ox + (i % 4) * s + s / 2, oy + (i / 4) * s + s / 2, s / 2 - 2, (phase % 8 < 6) ? 0 : (phase % 8) - 4);
        }
    }
}
