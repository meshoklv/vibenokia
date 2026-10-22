package numberplace;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Number Place: 9x9 number-placement logic puzzle. Puzzles are generated on
 * the phone (a few cells per frame) and always have exactly one solution.
 */
public class NumberPlaceGame extends Game {
    private static final int[] TARGET_GIVENS = { 40, 32, 25 };

    private final int[] sol = new int[81];
    private final int[] grid = new int[81];
    private final boolean[] given = new boolean[81];
    private final int[] work = new int[81];
    private final int[] order = new int[81];
    private int genPos, givens;
    private boolean generating;
    private int cur = 40;
    private boolean picker, direct;
    private int pick = 1, startFrame, solveCount;

    protected String name() { return "Number Place"; }

    protected String[] help() {
        return new String[] {
            "Fill the grid so every row, every column and every 3x3 box contains the digits 1 to 9 exactly once. Every puzzle has one solution.",
            "Keypad mode: 2/4/6/8 move the cursor, 5 opens the number picker (then press 1-9, or 0 to clear).",
            "Direct mode (press # to switch): the joystick moves, and digit keys write numbers straight into the cell. Handy on phones with a navigation key.",
            "Clashing digits are shown in red.",
            "- Controls",
            "2/4/6/8: move  5: number picker",
            "0: clear cell  #: keypad/direct mode",
        };
    }

    protected String[] modes() { return new String[] { "Easy", "Medium", "Hard" }; }

    protected boolean lowerIsBetter() { return true; }

    protected String formatScore(int s) { return Gfx.time(s, tickMs); }

    protected int accent() { return 0x5C9DFF; }

    protected void newGame() {
        makeSolution();
        for (int i = 0; i < 81; i++) {
            grid[i] = sol[i];
            given[i] = true;
            order[i] = i;
        }
        Rnd.shuffle(order);
        genPos = 0;
        givens = 81;
        generating = true;
        picker = false;
        cur = 40;
    }

    private void makeSolution() {
        int[] digits = { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        Rnd.shuffle(digits);
        int[] rows = perm(), cols = perm();
        boolean tr = Rnd.nextInt(2) == 0;
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int rr = rows[r], cc = cols[c];
                int v = digits[(rr * 3 + rr / 3 + cc) % 9];
                sol[tr ? c * 9 + r : r * 9 + c] = v;
            }
        }
    }

    /** Permutation of 0..8 that keeps rows/cols inside their band/stack. */
    private int[] perm() {
        int[] bands = { 0, 1, 2 };
        Rnd.shuffle(bands);
        int[] p = new int[9];
        for (int b = 0; b < 3; b++) {
            int[] in = { 0, 1, 2 };
            Rnd.shuffle(in);
            for (int k = 0; k < 3; k++) p[b * 3 + k] = bands[b] * 3 + in[k];
        }
        return p;
    }

    private static int box(int i) {
        return (i / 27) * 3 + (i % 9) / 3;
    }

    /** Count solutions of work[] up to 2 using bitmask backtracking with MRV. */
    private int count(int[] rowM, int[] colM, int[] boxM) {
        int best = -1, bestN = 10, bestMask = 0;
        for (int i = 0; i < 81; i++) {
            if (work[i] != 0) continue;
            int m = ~(rowM[i / 9] | colM[i % 9] | boxM[box(i)]) & 0x3FE;
            int n = gamekit.FMath.bits(m);
            if (n < bestN) {
                bestN = n;
                best = i;
                bestMask = m;
                if (n <= 1) break;
            }
        }
        if (best < 0) return 1;
        if (bestN == 0) return 0;
        int total = 0;
        int r = best / 9, c = best % 9, bx = box(best);
        for (int d = 1; d <= 9 && total < 2; d++) {
            int bit = 1 << d;
            if ((bestMask & bit) == 0) continue;
            work[best] = d;
            rowM[r] |= bit;
            colM[c] |= bit;
            boxM[bx] |= bit;
            total += count(rowM, colM, boxM);
            rowM[r] &= ~bit;
            colM[c] &= ~bit;
            boxM[bx] &= ~bit;
            work[best] = 0;
        }
        return total;
    }

    private boolean unique() {
        int[] rowM = new int[9], colM = new int[9], boxM = new int[9];
        for (int i = 0; i < 81; i++) {
            work[i] = given[i] ? sol[i] : 0;
            if (work[i] != 0) {
                int bit = 1 << work[i];
                rowM[i / 9] |= bit;
                colM[i % 9] |= bit;
                boxM[box(i)] |= bit;
            }
        }
        return count(rowM, colM, boxM) == 1;
    }

    private void generateStep() {
        for (int k = 0; k < 3 && generating; k++) {
            if (genPos >= 81 || givens <= TARGET_GIVENS[mode]) {
                generating = false;
                for (int i = 0; i < 81; i++) grid[i] = given[i] ? sol[i] : 0;
                startFrame = frame;
                return;
            }
            int i = order[genPos++];
            given[i] = false;
            if (unique()) givens--;
            else given[i] = true;
        }
    }

    private boolean conflict(int i) {
        int v = grid[i];
        if (v == 0) return false;
        for (int j = 0; j < 81; j++) {
            if (j != i && grid[j] == v && (j / 9 == i / 9 || j % 9 == i % 9 || box(j) == box(i))) return true;
        }
        return false;
    }

    private void set(int v) {
        if (given[cur]) {
            Sfx.bad();
            return;
        }
        grid[cur] = v;
        Sfx.click();
        for (int i = 0; i < 81; i++) if (grid[i] != sol[i]) return;
        score = Math.max(1, frame - startFrame);
        endGame(true);
    }

    protected void update() {
        if (generating) {
            generateStep();
            return;
        }
        // digit keys always carry their K_NUMn bit; a joystick press never does
        int d = digitPressed();
        if (picker) {
            if (d >= 0) {
                set(d);
                picker = false;
                return;
            }
            if ((pressed & K_LEFT) != 0) pick = pick == 0 ? 9 : pick - 1;
            if ((pressed & K_RIGHT) != 0) pick = pick == 9 ? 0 : pick + 1;
            if ((pressed & (K_UP | K_DOWN)) != 0) pick = (pick + 5) % 10;
            if ((pressed & K_FIRE) != 0) {
                set(pick);
                picker = false;
            }
            if ((pressed & (K_POUND | K_SOFT)) != 0) picker = false;
            return;
        }
        if ((pressed & K_POUND) != 0) {
            direct = !direct;
            Sfx.click();
        }
        if (direct && d >= 0) {
            set(d);
            return;
        }
        int nav = pressed;
        int dx = 0, dy = 0;
        if ((nav & K_LEFT) != 0) dx = -1;
        if ((nav & K_RIGHT) != 0) dx = 1;
        if ((nav & K_UP) != 0) dy = -1;
        if ((nav & K_DOWN) != 0) dy = 1;
        cur = ((cur / 9 + dy + 9) % 9) * 9 + (cur % 9 + dx + 9) % 9;
        if ((pressed & K_FIRE) != 0) {
            if (given[cur]) {
                Sfx.bad();
            } else {
                picker = true;
                pick = grid[cur] == 0 ? 5 : grid[cur];
            }
        }
        if (!direct && d == 0) set(0);
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int cell = Math.max(8, Math.min(W - 2, H - hud * 2 - 2) / 9);
        int size = cell * 9;
        int ox = (W - size) / 2, oy = hud + (H - hud * 2 - size) / 2;
        g.setColor(0x0F1626);
        g.fillRect(0, 0, W, H);
        if (generating) {
            Gfx.text(g, "Generating puzzle", W / 2, H / 2 - 14, Gfx.TC, Gfx.SMALL_B, 0xD0E0FF);
            Gfx.bar(g, W / 6, H / 2, W * 2 / 3, 6, genPos, 81, 0x5C9DFF, 0x1D2A44);
            Gfx.text(g, "unique solution check", W / 2, H / 2 + 10, Gfx.TC, Gfx.SMALL, 0x7F8FB0);
            return;
        }
        g.setColor(0xF4F1E8);
        g.fillRect(ox, oy, size, size);
        int curV = grid[cur];
        for (int i = 0; i < 81; i++) {
            int x = ox + (i % 9) * cell, y = oy + (i / 9) * cell;
            boolean sameLine = i / 9 == cur / 9 || i % 9 == cur % 9 || box(i) == box(cur);
            if (i == cur && (state == PLAY || state == PAUSE)) {
                g.setColor(0xFFD54F);
                g.fillRect(x, y, cell, cell);
            } else if (curV != 0 && grid[i] == curV) {
                g.setColor(0xC9DCFF);
                g.fillRect(x, y, cell, cell);
            } else if (sameLine) {
                g.setColor(0xE7E4DA);
                g.fillRect(x, y, cell, cell);
            }
            if (grid[i] != 0) {
                Font f = cell >= 16 ? Gfx.MEDIUM : Gfx.SMALL_B;
                g.setFont(f);
                g.setColor(given[i] ? 0x1A1A1A : (conflict(i) ? 0xE53935 : 0x1E5BD6));
                g.drawString(String.valueOf(grid[i]), x + cell / 2 + 1, y + (cell - f.getBaselinePosition()) / 2, Gfx.TC);
            }
        }
        for (int k = 0; k <= 9; k++) {
            g.setColor(k % 3 == 0 ? 0x22283A : 0xB8B4A8);
            g.drawLine(ox + k * cell, oy, ox + k * cell, oy + size);
            g.drawLine(ox, oy + k * cell, ox + size, oy + k * cell);
            if (k % 3 == 0 && k > 0 && k < 9) {
                g.drawLine(ox + k * cell + 1, oy, ox + k * cell + 1, oy + size);
                g.drawLine(ox, oy + k * cell + 1, ox + size, oy + k * cell + 1);
            }
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xD0E0FF);
        g.drawString(modes()[mode], 2, 1, Gfx.TL);
        g.drawString(Gfx.time(state == OVER ? score : frame - startFrame, tickMs), W - 2, 1, Gfx.TR);
        g.setFont(Gfx.SMALL);
        g.setColor(0x8FA3C8);
        String hint = W >= 170 ? (direct ? "DIRECT: digits write  # keypad" : "5 pick  0 clear  # direct")
                : (direct ? "DIRECT  #:keypad" : "5:pick 0:clr #:direct");
        g.drawString(hint, W / 2, H - hud + 1, Gfx.TC);
        if (picker) drawPicker(g);
    }

    private void drawPicker(Graphics g) {
        Font f = Gfx.SMALL_B;
        int c = Math.max(f.getHeight() + 4, Math.min(W / 6, 22));
        int pw = c * 5 + 6, ph = c * 2 + 6 + f.getHeight();
        int px = (W - pw) / 2, py = (H - ph) / 2;
        Gfx.panel(g, px, py, pw, ph, 0x1D2A44, 0x5C9DFF);
        g.setFont(f);
        g.setColor(0xD0E0FF);
        g.drawString("Choose 1-9 (0 clears)", W / 2, py + 2, Gfx.TC);
        for (int k = 0; k < 10; k++) {
            int v = k == 9 ? 0 : k + 1;
            int x = px + 3 + (k % 5) * c, y = py + 4 + f.getHeight() + (k / 5) * c;
            if (v == pick) {
                g.setColor(0xFFD54F);
                g.fillRect(x, y, c - 2, c - 2);
            }
            g.setColor(v == pick ? 0x1A1A1A : 0xFFFFFF);
            g.drawString(v == 0 ? "X" : String.valueOf(v), x + c / 2, y + (c - 2 - f.getBaselinePosition()) / 2, Gfx.TC);
        }
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cell = Math.max(8, Math.min(h / 3, w / 9));
        int x0 = x + (w - cell * 9) / 2, y0 = y + (h - cell * 3) / 2;
        g.setColor(0xF4F1E8);
        g.fillRect(x0, y0, cell * 9, cell * 3);
        g.setFont(Gfx.SMALL_B);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                int v = ((r * 3 + c) % 9) + 1;
                boolean show = ((c * 7 + r * 5 + clock / 10) % 4) != 0;
                if (show) {
                    g.setColor(((c + r) & 1) == 0 ? 0x1A1A1A : 0x1E5BD6);
                    g.drawString(String.valueOf(v), x0 + c * cell + cell / 2 + 1, y0 + r * cell + (cell - 7) / 2, Gfx.TC);
                }
            }
        }
        g.setColor(0x22283A);
        g.drawRect(x0, y0, cell * 9, cell * 3);
        g.drawLine(x0 + cell * 3, y0, x0 + cell * 3, y0 + cell * 3);
        g.drawLine(x0 + cell * 6, y0, x0 + cell * 6, y0 + cell * 3);
    }
}
