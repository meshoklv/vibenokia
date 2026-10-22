package keypadcheckers;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Keypad Checkers: English draughts with forced captures, multi-jumps and
 * kings, against an alpha-beta AI. You play red (moving up the board).
 */
public class CheckersGame extends Game {
    private static final int CAP = 1 << 18, PROMO = 1 << 19;
    private static final int[] DR = { -1, -1, 1, 1 }, DC = { -1, 1, -1, 1 };

    private final int[] b = new int[64]; // +1 red man, +2 red king, -1/-2 black
    private final int[][] buf = new int[16][64];
    private final int[] undoCap = new int[64];
    private int turn, cur = 42, sel = -1, jumper = -1, wait, lastFrom = -1, lastTo = -1;
    private int[] legal = new int[64];
    private int nLegal;

    protected String name() { return "Keypad Checkers"; }

    protected String[] help() {
        return new String[] {
            "English draughts: move your red men diagonally forward onto empty dark squares. Jump over an enemy piece to capture it.",
            "Captures are compulsory, and if a jump lands where another jump is possible you must keep jumping. Reach the far row to crown a king, which moves both ways.",
            "Win by capturing or blocking all of the CPU's pieces.",
            "- Controls",
            "2/4/6/8, 1/3/7/9: move cursor",
            "5: select piece / move  0: cancel",
        };
    }

    protected String[] modes() { return new String[] { "Easy", "Normal", "Hard" }; }

    protected boolean hasScore() { return false; }

    protected String footer() { return "Won " + saved[0] + "  Lost " + saved[1]; }

    protected int accent() { return 0xA47551; }

    protected void newGame() {
        for (int i = 0; i < 64; i++) {
            int r = i / 8, c = i % 8;
            boolean dark = (r + c) % 2 == 1;
            b[i] = !dark ? 0 : (r < 3 ? -1 : (r > 4 ? 1 : 0));
        }
        turn = 1;
        sel = -1;
        jumper = -1;
        lastFrom = lastTo = -1;
        refresh();
    }

    // -------------------------------------------------------------- rules

    private int gen(int side, int only, int[] out) {
        int n = 0;
        boolean anyCap = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int sq = 0; sq < 64; sq++) {
                if (only >= 0 && sq != only) continue;
                int v = b[sq];
                if (v * side <= 0) continue;
                boolean king = v == 2 || v == -2;
                int r = sq / 8, c = sq % 8;
                for (int d = 0; d < 4; d++) {
                    if (!king && (side > 0 ? DR[d] > 0 : DR[d] < 0)) continue;
                    int r1 = r + DR[d], c1 = c + DC[d];
                    if (r1 < 0 || r1 > 7 || c1 < 0 || c1 > 7) continue;
                    int t1 = r1 * 8 + c1;
                    if (pass == 0) {
                        int r2 = r1 + DR[d], c2 = c1 + DC[d];
                        if (r2 < 0 || r2 > 7 || c2 < 0 || c2 > 7) continue;
                        int t2 = r2 * 8 + c2;
                        if (b[t1] * side < 0 && b[t2] == 0) {
                            int promo = !king && (side > 0 ? r2 == 0 : r2 == 7) ? PROMO : 0;
                            out[n++] = sq | (t2 << 6) | (t1 << 12) | CAP | promo;
                            anyCap = true;
                        }
                    } else if (b[t1] == 0) {
                        int promo = !king && (side > 0 ? r1 == 0 : r1 == 7) ? PROMO : 0;
                        out[n++] = sq | (t1 << 6) | promo;
                    }
                }
            }
            if (anyCap || only >= 0) return n; // captures are compulsory; continuing jumps only capture
        }
        return n;
    }

    private void make(int m, int ply) {
        int from = m & 63, to = (m >> 6) & 63;
        b[to] = b[from];
        b[from] = 0;
        if ((m & CAP) != 0) {
            int c = (m >> 12) & 63;
            undoCap[ply] = b[c];
            b[c] = 0;
        }
        if ((m & PROMO) != 0) b[to] *= 2;
    }

    private void unmake(int m, int ply) {
        int from = m & 63, to = (m >> 6) & 63;
        if ((m & PROMO) != 0) b[to] /= 2;
        b[from] = b[to];
        b[to] = 0;
        if ((m & CAP) != 0) b[(m >> 12) & 63] = undoCap[ply];
    }

    private boolean canContinue(int m, int side) {
        if ((m & CAP) == 0 || (m & PROMO) != 0) return false;
        int[] tmp = buf[15];
        int n = gen(side, (m >> 6) & 63, tmp);
        return n > 0 && (tmp[0] & CAP) != 0;
    }

    private int evaluate(int side) {
        int s = 0;
        for (int i = 0; i < 64; i++) {
            int v = b[i];
            if (v == 0) continue;
            int r = i / 8;
            int val = (v == 2 || v == -2) ? 160 : 100 + (v > 0 ? (7 - r) : r) * 3;
            if ((v == 1 && r == 7) || (v == -1 && r == 0)) val += 8;
            s += v > 0 ? val : -val;
        }
        return s * side;
    }

    private int search(int depth, int alpha, int beta, int side, int only, int ply) {
        if (ply >= 14) return evaluate(side);
        int[] ms = buf[ply];
        int n = gen(side, only, ms);
        if (n == 0) return only >= 0 ? -search(depth - 1, -beta, -alpha, -side, -1, ply + 1) : -100000 + ply;
        if (depth <= 0 && only < 0 && (ms[0] & CAP) == 0) return evaluate(side);
        for (int i = 0; i < n; i++) {
            int m = ms[i];
            make(m, ply);
            int v;
            if (canContinue(m, side)) v = search(depth, alpha, beta, side, (m >> 6) & 63, ply + 1);
            else v = -search(depth - 1, -beta, -alpha, -side, -1, ply + 1);
            unmake(m, ply);
            if (v > alpha) alpha = v;
            if (alpha >= beta) break;
        }
        return alpha;
    }

    private int cpuMove() {
        int depth = mode == 0 ? 2 : (mode == 1 ? 4 : 6);
        int[] ms = buf[0];
        int n = gen(-1, jumper, ms);
        int best = -1000000, bestM = ms[0];
        int[] root = new int[n];
        System.arraycopy(ms, 0, root, 0, n);
        for (int i = 0; i < n; i++) {
            int m = root[i];
            make(m, 0);
            int v = canContinue(m, -1) ? search(depth, -1000000, 1000000, -1, (m >> 6) & 63, 1)
                    : -search(depth - 1, -1000000, 1000000, 1, -1, 1);
            unmake(m, 0);
            if (mode == 0) v += Rnd.range(-25, 25);
            if (v > best) {
                best = v;
                bestM = m;
            }
        }
        return bestM;
    }

    private void refresh() {
        nLegal = gen(turn, jumper, legal);
    }

    private void play(int m) {
        make(m, 0);
        lastFrom = m & 63;
        lastTo = (m >> 6) & 63;
        if ((m & CAP) != 0) Sfx.tone(60, 40); else Sfx.click();
        if ((m & PROMO) != 0) Sfx.good();
        if (canContinue(m, turn)) {
            jumper = lastTo;
            sel = turn > 0 ? jumper : -1;
        } else {
            jumper = -1;
            sel = -1;
            turn = -turn;
        }
        refresh();
        if (nLegal == 0) {
            boolean playerWon = turn < 0;
            saved[playerWon ? 0 : 1]++;
            headline = playerWon ? "YOU WIN!" : "CPU WINS";
            endGame(playerWon);
        }
    }

    protected void update() {
        if (turn < 0) {
            if (++wait < 10) return;
            wait = 0;
            play(cpuMove());
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
        if (digit(0) && jumper < 0) sel = -1;
        if ((pressed & K_FIRE) == 0) return;
        if (sel >= 0) {
            for (int i = 0; i < nLegal; i++) {
                if ((legal[i] & 63) == sel && ((legal[i] >> 6) & 63) == cur) {
                    play(legal[i]);
                    return;
                }
            }
        }
        if (jumper >= 0) {
            Sfx.bad();
            return;
        }
        boolean movable = false;
        for (int i = 0; i < nLegal; i++) if ((legal[i] & 63) == cur) movable = true;
        if (movable) {
            sel = cur;
            Sfx.click();
        } else {
            sel = -1;
            if (b[cur] > 0) Sfx.bad();
        }
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int s = Math.max(8, Math.min(W - 2, H - hud * 2) / 8);
        int ox = (W - s * 8) / 2, oy = hud + (H - hud * 2 - s * 8) / 2;
        g.setColor(0x2B1B10);
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < 64; i++) {
            int x = ox + (i % 8) * s, y = oy + (i / 8) * s;
            boolean dark = ((i / 8) + (i % 8)) % 2 == 1;
            int c = dark ? 0x6D4C41 : 0xE8D3B0;
            if (i == lastFrom || i == lastTo) c = 0x8D7A3E;
            if (i == sel) c = 0x558B2F;
            g.setColor(c);
            g.fillRect(x, y, s, s);
            int v = b[i];
            if (v != 0) {
                int r = s / 2 - 2;
                g.setColor(0x000000);
                Gfx.disc(g, x + s / 2 + 1, y + s / 2 + 1, r);
                g.setColor(v > 0 ? 0xD32F2F : 0x212121);
                Gfx.disc(g, x + s / 2, y + s / 2, r);
                g.setColor(v > 0 ? 0xEF5350 : 0x424242);
                Gfx.ring(g, x + s / 2, y + s / 2, r * 2 / 3);
                if (v == 2 || v == -2) {
                    g.setColor(0xFFD54F);
                    int k = Math.max(2, r / 2);
                    g.fillTriangle(x + s / 2 - k, y + s / 2 + k / 2, x + s / 2 + k, y + s / 2 + k / 2, x + s / 2, y + s / 2 - k);
                }
            }
        }
        if (sel >= 0) {
            g.setColor(0xC5E1A5);
            for (int i = 0; i < nLegal; i++) {
                if ((legal[i] & 63) != sel) continue;
                int t = (legal[i] >> 6) & 63;
                Gfx.disc(g, ox + (t % 8) * s + s / 2, oy + (t / 8) * s + s / 2, Math.max(2, s / 6));
            }
        }
        if (turn > 0 && state == PLAY) {
            g.setColor((clock & 4) == 0 ? 0xFFEB3B : 0xFFFFFF);
            g.drawRect(ox + (cur % 8) * s, oy + (cur / 8) * s, s - 1, s - 1);
        }
        int red = 0, black = 0;
        for (int i = 0; i < 64; i++) if (b[i] > 0) red++; else if (b[i] < 0) black++;
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xEF9A9A);
        g.drawString("Red " + red, 2, 1, Gfx.TL);
        g.setColor(0xBDBDBD);
        g.drawString(black + " Black", W - 2, 1, Gfx.TR);
        g.setFont(Gfx.SMALL);
        g.setColor(0xD7CCC8);
        String st = turn < 0 ? "CPU thinking..." : (jumper >= 0 ? "Keep jumping!" : (nLegal > 0 && (legal[0] & CAP) != 0 ? "You must capture" : "Your move"));
        if (state == PLAY) g.drawString(st, W / 2, H - hud + 1, Gfx.TC);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(10, Math.min(h / 2, w / 6));
        int ox = x + (w - s * 4) / 2, oy = y + (h - s * 2) / 2;
        for (int i = 0; i < 8; i++) {
            int cx = ox + (i % 4) * s, cy = oy + (i / 4) * s;
            boolean dark = ((i / 4) + (i % 4)) % 2 == 1;
            g.setColor(dark ? 0x6D4C41 : 0xE8D3B0);
            g.fillRect(cx, cy, s, s);
            if (dark) {
                int jump = (clock / 10) % 4 == i % 4 ? 2 : 0;
                g.setColor(i < 4 ? 0x212121 : 0xD32F2F);
                Gfx.disc(g, cx + s / 2, cy + s / 2 - jump, s / 2 - 2);
            }
        }
    }
}
