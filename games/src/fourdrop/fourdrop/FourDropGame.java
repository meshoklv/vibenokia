package fourdrop;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Four Drop: drop discs, connect four in a row. vs CPU (3 levels) or 2 players. */
public class FourDropGame extends Game {
    private static final int C = 7, R = 6;
    private static final int[] DEPTH = { 2, 4, 6 };
    private static final int[] ORDER = { 3, 2, 4, 1, 5, 0, 6 };

    private final int[] b = new int[C * R]; // 0 empty, 1 red, 2 yellow; row 0 = top
    private final int[] hgt = new int[C];
    private int turn, cursor = 3, moves;
    private int dropCol = -1, dropRow, dropY, dropPlayer;
    private int winMask0 = -1, winDx, winDy;
    private boolean cpuThinking;
    private int thinkWait;

    protected String name() { return "Four Drop"; }

    protected String[] help() {
        return new String[] {
            "Take turns dropping discs into the seven columns. The first to line up four of their discs horizontally, vertically or diagonally wins.",
            "Press a digit 1-7 to drop straight into that column, or move with the joystick and press the centre key.",
            "Play the phone at three strengths, or pass the phone between two players.",
            "- Controls",
            "1-7: drop in column",
            "Joystick left/right + select: drop",
        };
    }

    protected String[] modes() { return new String[] { "vs CPU Easy", "vs CPU Normal", "vs CPU Hard", "2 Players" }; }

    protected boolean hasScore() { return false; }

    protected String footer() { return "Won " + saved[0] + "  Lost " + saved[1]; }

    protected int accent() { return 0xFFCC33; }

    private boolean cpuGame() { return mode < 3; }

    protected void newGame() {
        for (int i = 0; i < C * R; i++) b[i] = 0;
        for (int c = 0; c < C; c++) hgt[c] = 0;
        moves = 0;
        saved[3] ^= 1; // alternate who starts
        turn = saved[3] == 0 ? 1 : 2;
        dropCol = -1;
        winMask0 = -1;
        cpuThinking = false;
        cursor = 3;
    }

    // ---------------------------------------------------------------- rules

    private boolean play(int col, int p) {
        if (hgt[col] >= R) return false;
        int row = R - 1 - hgt[col];
        b[row * C + col] = p;
        hgt[col]++;
        return true;
    }

    private void undo(int col) {
        hgt[col]--;
        b[(R - 1 - hgt[col]) * C + col] = 0;
    }

    private int count(int r, int c, int dr, int dc, int p) {
        int n = 0;
        r += dr;
        c += dc;
        while (r >= 0 && r < R && c >= 0 && c < C && b[r * C + c] == p) {
            n++;
            r += dr;
            c += dc;
        }
        return n;
    }

    private static final int[] DR = { 0, 1, 1, 1 }, DC = { 1, 0, 1, -1 };

    private boolean wins(int col) {
        int row = R - hgt[col];
        int p = b[row * C + col];
        for (int d = 0; d < 4; d++) {
            if (1 + count(row, col, DR[d], DC[d], p) + count(row, col, -DR[d], -DC[d], p) >= 4) {
                winDx = DC[d];
                winDy = DR[d];
                // start of the winning run
                int r = row, c = col;
                while (r - DR[d] >= 0 && r - DR[d] < R && c - DC[d] >= 0 && c - DC[d] < C && b[(r - DR[d]) * C + c - DC[d]] == p) {
                    r -= DR[d];
                    c -= DC[d];
                }
                winMask0 = r * C + c;
                return true;
            }
        }
        return false;
    }

    private int evalWindow(int a, int bb, int c, int d, int p) {
        // no array here: this runs thousands of times per CPU move
        int mine = (a == p ? 1 : 0) + (bb == p ? 1 : 0) + (c == p ? 1 : 0) + (d == p ? 1 : 0);
        int filled = (a != 0 ? 1 : 0) + (bb != 0 ? 1 : 0) + (c != 0 ? 1 : 0) + (d != 0 ? 1 : 0);
        int theirs = filled - mine;
        if (mine > 0 && theirs > 0) return 0;
        if (mine == 3) return 6;
        if (mine == 2) return 2;
        if (theirs == 3) return -5;
        if (theirs == 2) return -2;
        return 0;
    }

    private int evaluate(int p) {
        int s = 0;
        for (int r = 0; r < R; r++) if (b[r * C + 3] == p) s += 3; else if (b[r * C + 3] != 0) s -= 3;
        for (int r = 0; r < R; r++) {
            for (int c = 0; c < C; c++) {
                if (c + 3 < C) s += evalWindow(b[r * C + c], b[r * C + c + 1], b[r * C + c + 2], b[r * C + c + 3], p);
                if (r + 3 < R) s += evalWindow(b[r * C + c], b[(r + 1) * C + c], b[(r + 2) * C + c], b[(r + 3) * C + c], p);
                if (r + 3 < R && c + 3 < C) s += evalWindow(b[r * C + c], b[(r + 1) * C + c + 1], b[(r + 2) * C + c + 2], b[(r + 3) * C + c + 3], p);
                if (r + 3 < R && c - 3 >= 0) s += evalWindow(b[r * C + c], b[(r + 1) * C + c - 1], b[(r + 2) * C + c - 2], b[(r + 3) * C + c - 3], p);
            }
        }
        return s;
    }

    private int negamax(int depth, int alpha, int beta, int p, int filled) {
        if (filled == C * R) return 0;
        for (int k = 0; k < C; k++) {
            int c = ORDER[k];
            if (hgt[c] >= R) continue;
            play(c, p);
            boolean w = winsQuiet(c);
            undo(c);
            if (w) return 10000 + depth;
        }
        if (depth == 0) return evaluate(p);
        for (int k = 0; k < C; k++) {
            int c = ORDER[k];
            if (hgt[c] >= R) continue;
            play(c, p);
            int v = -negamax(depth - 1, -beta, -alpha, 3 - p, filled + 1);
            undo(c);
            if (v > alpha) alpha = v;
            if (alpha >= beta) break;
        }
        return alpha;
    }

    private boolean winsQuiet(int col) {
        int row = R - hgt[col];
        int p = b[row * C + col];
        for (int d = 0; d < 4; d++) {
            if (1 + count(row, col, DR[d], DC[d], p) + count(row, col, -DR[d], -DC[d], p) >= 4) return true;
        }
        return false;
    }

    private int cpuMove() {
        int p = 2, best = -100000, bestCol = 3;
        int depth = DEPTH[mode];
        for (int k = 0; k < C; k++) {
            int c = ORDER[k];
            if (hgt[c] >= R) continue;
            play(c, p);
            int v = winsQuiet(c) ? 20000 : -negamax(depth - 1, -100000, 100000, 1, moves + 1);
            undo(c);
            if (mode == 0) v += Rnd.range(-3, 3);
            if (v > best) {
                best = v;
                bestCol = c;
            }
        }
        return bestCol;
    }

    // --------------------------------------------------------------- flow

    private void startDrop(int col, int p) {
        if (hgt[col] >= R) {
            Sfx.bad();
            return;
        }
        dropCol = col;
        dropRow = R - 1 - hgt[col];
        dropY = -1 << 8;
        dropPlayer = p;
        Sfx.click();
    }

    protected void update() {
        if (dropCol >= 0) {
            dropY += 96;
            if ((dropY >> 8) >= dropRow) {
                int col = dropCol;
                dropCol = -1;
                play(col, dropPlayer);
                moves++;
                Sfx.hit();
                if (wins(col)) {
                    boolean humanWon = !cpuGame() || dropPlayer == 1;
                    if (cpuGame()) saved[humanWon ? 0 : 1]++;
                    headline = cpuGame() ? (humanWon ? "YOU WIN!" : "CPU WINS") : (dropPlayer == 1 ? "RED WINS!" : "YELLOW WINS!");
                    endGame(humanWon);
                    return;
                }
                if (moves == C * R) {
                    headline = "DRAW";
                    endGame(false);
                    return;
                }
                turn = 3 - turn;
            }
            return;
        }
        if (cpuGame() && turn == 2) {
            if (!cpuThinking) {
                cpuThinking = true;
                thinkWait = 6;
                return;
            }
            if (--thinkWait > 0) return;
            cpuThinking = false;
            int col = cpuMove();
            cursor = col;
            startDrop(col, 2);
            return;
        }
        int d = digitPressed();
        if (d >= 1 && d <= 7) {
            cursor = d - 1;
            startDrop(cursor, turn);
            return;
        }
        if (d < 0) {
            if ((pressed & K_LEFT) != 0) cursor = (cursor + C - 1) % C;
            if ((pressed & K_RIGHT) != 0) cursor = (cursor + 1) % C;
            if ((pressed & K_FIRE) != 0) startDrop(cursor, turn);
        }
    }

    private int color(int p) {
        return p == 1 ? 0xE53935 : 0xFFCA28;
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 3;
        int cell = Math.max(10, Math.min((W - 4) / C, (H - hud * 2 - 4) / (R + 1)));
        int bw = cell * C, bh = cell * R;
        int ox = (W - bw) / 2, oy = H - bh - hud - 2;
        g.setColor(0x10213F);
        g.fillRect(0, 0, W, H);
        // falling disc behind board
        if (dropCol >= 0) {
            g.setColor(color(dropPlayer));
            Gfx.disc(g, ox + dropCol * cell + cell / 2, oy + (dropY * cell >> 8) + cell / 2, cell / 2 - 2);
        }
        g.setColor(0x1E5FC9);
        g.fillRect(ox - 2, oy - 2, bw + 4, bh + 4);
        for (int r = 0; r < R; r++) {
            for (int c = 0; c < C; c++) {
                int v = b[r * C + c];
                int x = ox + c * cell + cell / 2, y = oy + r * cell + cell / 2;
                g.setColor(v == 0 ? 0x0B1530 : color(v));
                Gfx.disc(g, x, y, cell / 2 - 2);
                if (v != 0) {
                    g.setColor(Gfx.shade(color(v), 35));
                    g.fillArc(x - cell / 4, y - cell / 4, cell / 4, cell / 4, 0, 360);
                }
            }
        }
        if (winMask0 >= 0 && (clock & 4) == 0) {
            g.setColor(0xFFFFFF);
            for (int k = 0; k < 4; k++) {
                int r = winMask0 / C + winDy * k, c = winMask0 % C + winDx * k;
                Gfx.ring(g, ox + c * cell + cell / 2, oy + r * cell + cell / 2, cell / 2 - 2);
            }
        }
        // cursor disc above the board
        if (state == PLAY && dropCol < 0 && !(cpuGame() && turn == 2)) {
            g.setColor(color(turn));
            int bob = (clock & 8) == 0 ? 0 : 1;
            Gfx.disc(g, ox + cursor * cell + cell / 2, oy - cell / 2 - 2 + bob, cell / 2 - 3);
        }
        g.setFont(Gfx.SMALL);
        g.setColor(0x7F9CC9);
        for (int c = 0; c < C; c++) g.drawString(String.valueOf(c + 1), ox + c * cell + cell / 2 + 1, oy + bh + 3, Gfx.TC);
        g.setFont(Gfx.SMALL_B);
        String who;
        if (cpuGame()) who = turn == 1 ? "Your turn" : (cpuThinking ? "CPU thinking..." : "CPU");
        else who = turn == 1 ? "Red to play" : "Yellow to play";
        g.setColor(color(turn));
        g.drawString(who, W / 2, 1, Gfx.TC);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cell = Math.max(8, Math.min(h / 3, w / 8));
        int ox = x + (w - cell * 7) / 2, oy = y + (h - cell * 2) / 2;
        g.setColor(0x1E5FC9);
        g.fillRect(ox - 2, oy - 2, cell * 7 + 4, cell * 2 + 4);
        for (int c = 0; c < 7; c++) {
            for (int r = 0; r < 2; r++) {
                int v = ((c * 3 + r + clock / 15) % 5);
                g.setColor(v == 0 ? 0xE53935 : (v == 1 ? 0xFFCA28 : 0x0B1530));
                Gfx.disc(g, ox + c * cell + cell / 2, oy + r * cell + cell / 2, cell / 2 - 1);
            }
        }
    }
}
