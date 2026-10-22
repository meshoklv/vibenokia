package pegjump;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Peg Jump: peg solitaire. Jump a peg over a neighbour into an empty hole
 * to remove the jumped peg; finish with as few pegs as possible. Boards are
 * 7x7 masks: the English cross and a diamond-ish "Triangle" variant drawn on
 * the same grid.
 */
public class PegGame extends Game {
    private static final int N = 7;
    private static final String[] CROSS = {
        "..XXX..", "..XXX..", "XXXXXXX", "XXXOXXX", "XXXXXXX", "..XXX..", "..XXX..",
    };
    private static final String[] TRI = {
        "O......", "XX.....", "XXX....", "XXXX...", "XXXXX..", ".......", ".......",
    };
    private static final String[] PLUS = {
        "...X...", "..XXX..", ".XXXXX.", "XXXOXXX", ".XXXXX.", "..XXX..", "...X...",
    };
    private static final String[][] BOARDS = { CROSS, PLUS, TRI };

    private final int[][] hole = new int[N][N]; // -1 none, 0 empty, 1 peg
    private final int[] undoA = new int[64], undoB = new int[64], undoC = new int[64];
    private int undoN, cr, cc, held_, pegs, tri;
    private boolean picked;
    private String note = "";

    protected String name() { return "Peg Jump"; }

    protected String[] help() {
        return new String[] {
            "Peg solitaire. Pick up a peg and jump it over a neighbouring peg into an empty hole; the peg you jumped over is removed.",
            "Keep jumping until no moves remain. One peg left is a perfect game, and one peg in the centre hole is a master finish.",
            "The Triangle board also allows diagonal jumps along its slopes.",
            "- Controls",
            "2/4/6/8: move cursor",
            "5: pick up / put down peg",
            "then 2/4/6/8: jump that way",
            "0: undo",
        };
    }

    protected String[] modes() { return new String[] { "Cross", "Diamond", "Triangle" }; }

    protected int accent() { return 0xD7A86E; }

    protected boolean lowerIsBetter() { return true; }

    protected void newGame() {
        String[] b = BOARDS[mode];
        tri = mode == 2 ? 1 : 0;
        pegs = 0;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
            char ch = b[r].charAt(c);
            hole[r][c] = ch == 'X' ? 1 : (ch == 'O' ? 0 : -1);
            if (ch == 'X') pegs++;
        }
        cr = tri == 1 ? 2 : 3;
        cc = tri == 1 ? 1 : 3;
        if (hole[cr][cc] < 0) { cr = 3; cc = 3; }
        picked = false;
        undoN = 0;
        score = pegs;
        note = "";
    }

    private boolean valid(int r, int c) {
        return r >= 0 && r < N && c >= 0 && c < N && hole[r][c] >= 0;
    }

    private boolean canJump(int r, int c, int dr, int dc) {
        int mr = r + dr, mc = c + dc, tr = r + dr * 2, tc = c + dc * 2;
        return valid(tr, tc) && hole[r][c] == 1 && hole[mr][mc] == 1 && hole[tr][tc] == 0;
    }

    private static final int[] DR = { -1, 1, 0, 0, -1, 1 }, DC = { 0, 0, -1, 1, -1, 1 };

    private boolean anyMove() {
        int dirs = tri == 1 ? 6 : 4;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) for (int k = 0; k < dirs; k++) if (canJump(r, c, DR[k], DC[k])) return true;
        return false;
    }

    private void jump(int dr, int dc) {
        if (!canJump(cr, cc, dr, dc)) {
            Sfx.bad();
            note = "Can't jump that way";
            return;
        }
        hole[cr][cc] = 0;
        hole[cr + dr][cc + dc] = 0;
        hole[cr + dr * 2][cc + dc * 2] = 1;
        if (undoN < undoA.length) {
            undoA[undoN] = cr * N + cc;
            undoB[undoN] = (cr + dr) * N + cc + dc;
            undoC[undoN++] = (cr + dr * 2) * N + cc + dc * 2;
        }
        cr += dr * 2;
        cc += dc * 2;
        pegs--;
        score = pegs;
        picked = false;
        note = "";
        Sfx.tone(70 + (32 - pegs) / 2, 30);
        if (!anyMove()) finish();
    }

    private void finish() {
        boolean centre = tri == 0 && hole[3][3] == 1;
        if (pegs == 1) {
            headline = centre ? "MASTER FINISH!" : "ONE PEG LEFT!";
            endGame(true);
        } else {
            headline = pegs + " PEGS LEFT";
            endGame(pegs <= 3);
        }
    }

    protected String formatScore(int s) { return s + (s == 1 ? " peg" : " pegs"); }

    protected void update() {
        if (digit(0) && undoN > 0) {
            undoN--;
            hole[undoA[undoN] / N][undoA[undoN] % N] = 1;
            hole[undoB[undoN] / N][undoB[undoN] % N] = 1;
            hole[undoC[undoN] / N][undoC[undoN] % N] = 0;
            cr = undoA[undoN] / N;
            cc = undoA[undoN] % N;
            pegs++;
            score = pegs;
            picked = false;
            Sfx.click();
            return;
        }
        if (picked) {
            if ((pressed & K_UP) != 0) jump(-1, 0);
            else if ((pressed & K_DOWN) != 0) jump(1, 0);
            else if ((pressed & K_LEFT) != 0) jump(0, -1);
            else if ((pressed & K_RIGHT) != 0) jump(0, 1);
            else if (tri == 1 && digit(1)) jump(-1, -1);
            else if (tri == 1 && digit(9)) jump(1, 1);
            else if ((pressed & K_FIRE) != 0) { picked = false; Sfx.click(); }
            return;
        }
        int nr = cr, nc = cc;
        if ((pressed & K_UP) != 0) nr--;
        if ((pressed & K_DOWN) != 0) nr++;
        if ((pressed & K_LEFT) != 0) nc--;
        if ((pressed & K_RIGHT) != 0) nc++;
        if (nr != cr || nc != cc) {
            // skip over gaps in the board shape
            int dr = nr - cr, dc = nc - cc;
            while (nr >= 0 && nr < N && nc >= 0 && nc < N && hole[nr][nc] < 0) { nr += dr; nc += dc; }
            if (valid(nr, nc)) { cr = nr; cc = nc; }
        }
        if ((pressed & K_FIRE) != 0) {
            if (hole[cr][cc] == 1) {
                picked = true;
                note = tri == 1 ? "Jump: 2/4/6/8, 1/9 diagonal" : "Jump which way?";
                Sfx.click();
            } else Sfx.bad();
        }
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int s = Math.min((W - 8) / N, (H - hud * 2 - 4) / N);
        int ox = (W - s * N) / 2, oy = hud + (H - hud * 2 - s * N) / 2;
        g.setColor(0x3E2723);
        g.fillRect(0, 0, W, H);
        g.setColor(0x8D6E63);
        g.fillRoundRect(ox - 3, oy - 3, s * N + 6, s * N + 6, 10, 10);
        g.setColor(0xA1887F);
        g.fillRoundRect(ox - 1, oy - 1, s * N + 2, s * N + 2, 8, 8);
        int pr = Math.max(2, s * 3 / 8);
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
            if (hole[r][c] < 0) continue;
            int x = ox + c * s + s / 2, y = oy + r * s + s / 2;
            g.setColor(0x4E342E);
            Gfx.disc(g, x, y, Math.max(2, s / 5));
            boolean cur = r == cr && c == cc;
            if (hole[r][c] == 1) {
                int lift = cur && picked ? 2 : 0;
                g.setColor(0x1B1B1B);
                Gfx.disc(g, x + 1, y + 1, pr);
                g.setColor(cur && picked ? 0xFFD54F : 0xC62828);
                Gfx.disc(g, x, y - lift, pr);
                g.setColor(cur && picked ? 0xFFF59D : 0xEF5350);
                Gfx.disc(g, x - pr / 3, y - pr / 3 - lift, Math.max(1, pr / 3));
            }
            if (cur) {
                g.setColor(0xFFFFFF);
                g.drawRect(ox + c * s, oy + r * s, s - 1, s - 1);
            }
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFE0B2);
        g.drawString("Pegs " + pegs, 2, 1, Gfx.TL);
        g.drawString(modes()[mode], W - 2, 1, Gfx.TR);
        Gfx.text(g, note.length() > 0 ? note : "5 pick  0 undo", W / 2, H - hud + 1, Gfx.TC, Gfx.SMALL, 0xD7CCC8);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(6, Math.min(w / 8, h / 3));
        int bx = x + w / 2 - s * 2, by = y + h / 2 - s / 2;
        int t = (clock / 8) % 4;
        for (int k = 0; k < 4; k++) {
            g.setColor(0x4E342E);
            Gfx.disc(g, bx + k * s + s / 2, by + s / 2, s / 4);
        }
        g.setColor(0xC62828);
        Gfx.disc(g, bx + (t < 2 ? 0 : 2) * s + s / 2, by + s / 2 - (t == 1 ? s / 2 : 0), s * 3 / 8);
        if (t < 2) Gfx.disc(g, bx + s + s / 2, by + s / 2, s * 3 / 8);
        Gfx.disc(g, bx + 3 * s + s / 2, by + s / 2, s * 3 / 8);
    }
}
