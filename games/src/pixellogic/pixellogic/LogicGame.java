package pixellogic;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Pixel Logic: picture-logic (nonogram) puzzles. The numbers beside each
 * row and above each column give the runs of filled cells, in order.
 * Any grid matching every clue is accepted.
 */
public class LogicGame extends Game {
    private static final String[][] SMALL = {
        { ".#.#.", "#####", "#####", ".###.", "..#.." },         // heart
        { "..#..", ".###.", "#####", ".#.#.", ".###." },         // house
        { "#...#", ".#.#.", "..#..", ".#.#.", "#...#" },         // cross
        { ".###.", "#.#.#", "#####", "#.#.#", ".###." },         // face
        { "..#..", ".###.", "#####", "..#..", "..#.." },         // arrow
        { "#.#.#", ".###.", "##.##", ".###.", "#.#.#" },         // snowflake
    };
    private static final String[][] BIG = {
        { "...####...", "..######..", ".##.##.##.", "##########", "##########", "...#..#...", "...#..#...", "..##..##..", "..........", ".........." }, // mushroom
        { "....##....", "...####...", "..######..", ".########.", "##########", "...####...", "...#..#...", "...#..#...", "...####...", ".........." }, // tree house
        { "..........", "....#.....", "....##....", "....###...", "....####..", "....#.....", "#########.", ".#######..", "..#####...", "~~~~~~~~~~" }, // boat
        { "...####...", "..#....#..", ".#.#..#.#.", ".#......#.", ".#.####.#.", "..#....#..", "...####...", "....##....", "...####...", ".........." }, // robot head
        { "..##..##..", ".####.###.", "##########", "##########", ".########.", "..######..", "...####...", "....##....", "..........", ".........." }, // big heart
        { "....##....", "...#..#...", "...####...", "...#..#...", "...####...", "..######..", ".########.", ".##.##.##.", ".#......#.", ".........." }, // rocket
        { "......##..", "......####", ".....##.##", "..########", ".#########", "##########", "#.#.#.#.#.", "..........", "..........", ".........." }, // whale
        { "#........#", "##......##", "###.##.###", "##########", "#.######.#", "..######..", "..##..##..", "..#....#..", ".##....##.", ".........." }, // cat
    };

    private int n, puzzle, cur;
    private boolean[] sol;
    private byte[] cell; // 0 empty, 1 filled, 2 marked empty
    private int[][] rowClue, colClue;
    private int maxRow, maxCol, solvedT;

    protected String name() { return "Pixel Logic"; }

    protected String[] help() {
        return new String[] {
            "Fill in cells to reveal a hidden picture. The numbers beside a row list the runs of filled cells in that row, in order; '3 1' means three filled, a gap, then one filled. Columns work the same way, read top to bottom.",
            "Use X marks for cells you know are empty. A clue turns grey when its line matches.",
            "Solve one puzzle to unlock the next.",
            "- Controls",
            "2/4/6/8: move",
            "5: fill / clear  0: mark X",
        };
    }

    protected String[] modes() { return new String[] { "5x5 puzzles", "10x10 puzzles" }; }

    protected boolean hasScore() { return false; }

    protected String footer() { return "Solved " + Math.min(saved[0], SMALL.length) + "/" + SMALL.length + "  " + Math.min(saved[1], BIG.length) + "/" + BIG.length; }

    protected int accent() { return 0x4C8DFF; }

    protected void newGame() {
        String[][] set = mode == 0 ? SMALL : BIG;
        puzzle = saved[mode] % set.length;
        load(set[puzzle]);
    }

    private void load(String[] art) {
        n = art.length;
        sol = new boolean[n * n];
        cell = new byte[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) sol[y * n + x] = art[y].charAt(x) == '#' || art[y].charAt(x) == '~';
        rowClue = new int[n][];
        colClue = new int[n][];
        maxRow = maxCol = 1;
        for (int i = 0; i < n; i++) {
            rowClue[i] = runs(i, true);
            colClue[i] = runs(i, false);
            maxRow = Math.max(maxRow, rowClue[i].length);
            maxCol = Math.max(maxCol, colClue[i].length);
        }
        cur = 0;
        solvedT = 0;
    }

    private int[] runs(int line, boolean row) {
        int[] tmp = new int[n];
        int k = 0, run = 0;
        for (int i = 0; i < n; i++) {
            boolean f = row ? sol[line * n + i] : sol[i * n + line];
            if (f) run++;
            else if (run > 0) { tmp[k++] = run; run = 0; }
        }
        if (run > 0) tmp[k++] = run;
        if (k == 0) return new int[] { 0 };
        int[] out = new int[k];
        System.arraycopy(tmp, 0, out, 0, k);
        return out;
    }

    private boolean lineOk(int line, boolean row) {
        int[] clue = row ? rowClue[line] : colClue[line];
        int k = 0, run = 0;
        for (int i = 0; i <= n; i++) {
            boolean f = i < n && (row ? cell[line * n + i] == 1 : cell[i * n + line] == 1);
            if (f) run++;
            else if (run > 0) {
                if (k >= clue.length || clue[k] != run) return false;
                k++;
                run = 0;
            }
        }
        return k == clue.length || (clue.length == 1 && clue[0] == 0 && k == 0);
    }

    protected void update() {
        if (solvedT > 0) {
            if (--solvedT == 0 || (pressed & K_FIRE) != 0) {
                solvedT = 0;
                String[][] set = mode == 0 ? SMALL : BIG;
                if (puzzle + 1 >= set.length) {
                    headline = "ALL SOLVED!";
                    endGame(true);
                    return;
                }
                puzzle++;
                load(set[puzzle]);
            }
            return;
        }
        int x = cur % n, y = cur / n;
        if ((pressed & K_LEFT) != 0) x = (x + n - 1) % n;
        if ((pressed & K_RIGHT) != 0) x = (x + 1) % n;
        if ((pressed & K_UP) != 0) y = (y + n - 1) % n;
        if ((pressed & K_DOWN) != 0) y = (y + 1) % n;
        cur = y * n + x;
        if ((pressed & K_FIRE) != 0) {
            cell[cur] = (byte) (cell[cur] == 1 ? 0 : 1);
            Sfx.click();
            check();
        } else if (digit(0) || (pressed & K_POUND) != 0) {
            cell[cur] = (byte) (cell[cur] == 2 ? 0 : 2);
            Sfx.tone(60, 15);
        }
    }

    private void check() {
        for (int i = 0; i < n; i++) if (!lineOk(i, true) || !lineOk(i, false)) return;
        solvedT = 60;
        if (saved[mode] < puzzle + 1) saved[mode] = puzzle + 1;
        persist();
        Sfx.win();
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int cw = 6; // clue glyph width
        int clueW = maxRow * cw * (n > 5 ? 1 : 2) + 4;
        int clueH = maxCol * (Gfx.SMALL.getHeight() - 1) + 3;
        int s = Math.max(5, Math.min((W - clueW - 4) / n, (H - hud - clueH - 4) / n));
        int ox = clueW + (W - clueW - s * n) / 2, oy = hud + clueH + (H - hud - clueH - s * n) / 2;
        g.setColor(0x10182A);
        g.fillRect(0, 0, W, H);
        g.setFont(Gfx.SMALL);
        int cy = cur / n, cx = cur % n;
        for (int i = 0; i < n; i++) {
            boolean rok = lineOk(i, true), cok = lineOk(i, false);
            // row clues, right-aligned before the grid
            int[] rc = rowClue[i];
            int x = ox - 3;
            for (int k = rc.length - 1; k >= 0; k--) {
                String t = String.valueOf(rc[k]);
                g.setColor(rok ? 0x556078 : (i == cy ? 0xFFEB3B : 0xDDE6F5));
                g.drawString(t, x, oy + i * s + (s - 7) / 2, Gfx.TR);
                x -= Gfx.SMALL.stringWidth(t) + 2;
            }
            int[] cc = colClue[i];
            int y = oy - 2 - Gfx.SMALL.getHeight() + 2;
            for (int k = cc.length - 1; k >= 0; k--) {
                g.setColor(cok ? 0x556078 : (i == cx ? 0xFFEB3B : 0xDDE6F5));
                g.drawString(String.valueOf(cc[k]), ox + i * s + s / 2 + 1, y - (Gfx.SMALL.getHeight() - 1) * (cc.length - 1 - k), Gfx.TC);
            }
        }
        for (int i = 0; i < n * n; i++) {
            int x = ox + (i % n) * s, y = oy + (i / n) * s;
            if (solvedT > 0) {
                g.setColor(sol[i] ? 0x4C8DFF : 0xF5F5F5);
                g.fillRect(x, y, s, s);
                continue;
            }
            g.setColor(cell[i] == 1 ? 0x263A70 : 0xF0F2F8);
            g.fillRect(x, y, s, s);
            if (cell[i] == 2) {
                g.setColor(0xE57373);
                g.drawLine(x + 1, y + 1, x + s - 2, y + s - 2);
                g.drawLine(x + s - 2, y + 1, x + 1, y + s - 2);
            }
        }
        g.setColor(0x9AA5BD);
        for (int k = 0; k <= n; k++) {
            g.setColor(k % 5 == 0 ? 0x3A4A6E : 0xB8C2D8);
            g.drawLine(ox + k * s, oy, ox + k * s, oy + n * s);
            g.drawLine(ox, oy + k * s, ox + n * s, oy + k * s);
        }
        if (solvedT == 0) {
            g.setColor((clock & 4) == 0 ? 0xFF6F00 : 0xFFB300);
            g.drawRect(ox + cx * s, oy + cy * s, s, s);
            g.drawRect(ox + cx * s + 1, oy + cy * s + 1, s - 2, s - 2);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString((mode == 0 ? "5x5 #" : "10x10 #") + (puzzle + 1), 2, 1, Gfx.TL);
        if (solvedT > 0) Gfx.shadowText(g, "SOLVED!", W / 2, 1, Gfx.TC, Gfx.SMALL_B, 0x76FF03, 0x000000);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        String[] art = SMALL[(clock / 40) % SMALL.length];
        int s = Math.max(4, Math.min(h, w) / 6);
        int ox = x + (w - s * 5) / 2, oy = y + (h - s * 5) / 2;
        int reveal = (clock % 40);
        for (int i = 0; i < 25; i++) {
            boolean f = art[i / 5].charAt(i % 5) == '#';
            g.setColor(f && i < reveal ? 0x4C8DFF : 0xF0F2F8);
            g.fillRect(ox + (i % 5) * s, oy + (i / 5) * s, s - 1, s - 1);
        }
    }
}
