package cellsolitaire;

import gamekit.Cards;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Cell Solitaire: all cards face up, four free cells to park cards in.
 * Slots 0-3 free cells, 4-7 foundations, 8-15 columns.
 */
public class CellGame extends Game {
    private final int[][] col = new int[8][24];
    private final int[] colN = new int[8];
    private final int[] cell = new int[4];
    private final int[] found = new int[4];
    private final int[] deck = new int[52];
    private int cur = 8, depth = 1, hold = -1, holdCount, moves, autoT;

    protected String name() { return "Cell Solitaire"; }

    protected String[] help() {
        return new String[] {
            "Every card is dealt face up into eight columns. Build the four foundations (top right) up by suit from Ace to King.",
            "In the columns, stack cards downward in alternating colours. Any card can go into an empty column. The four free cells (top left) each hold one card.",
            "You can move several cards at once if you have enough free cells and empty columns to shuffle them through. Almost every deal can be won!",
            "- Controls",
            "4/6: move  2/8: rows / grow run",
            "5: pick up / drop",
            "#: to foundation  0: cancel",
        };
    }

    protected boolean lowerIsBetter() { return true; }

    protected String formatScore(int s) { return Gfx.time(s, tickMs); }

    protected int accent() { return 0x43AA8B; }

    protected void newGame() {
        Cards.shuffle(deck);
        for (int c = 0; c < 8; c++) colN[c] = 0;
        for (int i = 0; i < 52; i++) col[i % 8][colN[i % 8]++] = deck[i];
        for (int k = 0; k < 4; k++) {
            cell[k] = -1;
            found[k] = 0;
        }
        cur = 8;
        depth = 1;
        hold = -1;
        moves = 0;
        autoT = 0;
    }

    private static boolean red(int c) { return Cards.red(c); }

    private boolean stackable(int lower, int upper) {
        return red(lower) != red(upper) && Cards.rank(upper) == Cards.rank(lower) + 1;
    }

    /** Length of the ordered run at the bottom of column c. */
    private int runLen(int c) {
        int n = colN[c];
        if (n == 0) return 0;
        int k = 1;
        while (k < n && stackable(col[c][n - k], col[c][n - k - 1])) k++;
        return k;
    }

    private int maxMovable(boolean toEmpty) {
        int free = 0, empty = 0;
        for (int k = 0; k < 4; k++) if (cell[k] < 0) free++;
        for (int c = 0; c < 8; c++) if (colN[c] == 0) empty++;
        if (toEmpty) empty--;
        return (free + 1) << Math.max(0, empty);
    }

    private int heldTop() {
        if (hold < 4) return cell[hold];
        int c = hold - 8;
        return col[c][colN[c] - holdCount];
    }

    private void removeHeld() {
        if (hold < 4) cell[hold] = -1;
        else colN[hold - 8] -= holdCount;
    }

    private boolean toFoundation(int slot) {
        int card;
        if (slot < 4) card = cell[slot];
        else if (slot >= 8 && colN[slot - 8] > 0) card = col[slot - 8][colN[slot - 8] - 1];
        else return false;
        if (card < 0 || Cards.rank(card) != found[Cards.suit(card)]) return false;
        if (slot < 4) cell[slot] = -1;
        else colN[slot - 8]--;
        found[Cards.suit(card)]++;
        moves++;
        Sfx.tone(76 + Cards.rank(card) / 2, 25);
        check();
        return true;
    }

    private void check() {
        if (found[0] + found[1] + found[2] + found[3] == 52) {
            score = frame;
            headline = "SOLVED!";
            endGame(true);
        }
    }

    /** Safe auto-play: move cards to foundations when no lower card of the other colour still needs them. */
    private boolean autoStep() {
        for (int slot = 0; slot < 16; slot++) {
            if (slot >= 4 && slot < 8) continue;
            int card = slot < 4 ? cell[slot] : (colN[slot - 8] > 0 ? col[slot - 8][colN[slot - 8] - 1] : -1);
            if (card < 0) continue;
            int r = Cards.rank(card), s = Cards.suit(card);
            if (r != found[s]) continue;
            boolean safe = r <= 1;
            if (!safe) {
                int need = r - 1;
                boolean isRed = red(card);
                safe = true;
                for (int o = 0; o < 4; o++) {
                    boolean oRed = o == 1 || o == 2;
                    if (oRed != isRed && found[o] < need) safe = false;
                }
            }
            if (safe) return toFoundation(slot);
        }
        return false;
    }

    protected void update() {
        if (autoT > 0) {
            if (--autoT == 0 && autoStep() && state == PLAY) autoT = 3;
            return;
        }
        boolean top = cur < 8;
        if ((pressed & K_LEFT) != 0) { cur = top ? (cur + 7) % 8 : 8 + (cur - 8 + 7) % 8; depth = 1; }
        if ((pressed & K_RIGHT) != 0) { cur = top ? (cur + 1) % 8 : 8 + (cur - 8 + 1) % 8; depth = 1; }
        if ((pressed & K_UP) != 0) {
            if (!top && hold < 0 && depth < runLen(cur - 8)) depth++;
            else if (!top) { cur -= 8; depth = 1; }
        }
        if ((pressed & K_DOWN) != 0) {
            if (top) cur += 8;
            else if (depth > 1) depth--;
        }
        if (digit(0)) hold = -1;
        if ((pressed & K_POUND) != 0 && hold < 0) {
            if (toFoundation(cur)) autoT = 4;
            else Sfx.bad();
            return;
        }
        if ((pressed & K_FIRE) == 0) return;
        if (hold < 0) {
            if (cur < 4 && cell[cur] >= 0) { hold = cur; holdCount = 1; Sfx.click(); }
            else if (cur >= 8 && colN[cur - 8] > 0) { hold = cur; holdCount = Math.min(depth, runLen(cur - 8)); Sfx.click(); }
            return;
        }
        int card = heldTop();
        boolean ok = false;
        if (cur < 4) {
            if (cell[cur] < 0 && holdCount == 1) {
                removeHeld();
                cell[cur] = card;
                ok = true;
            }
        } else if (cur < 8) {
            if (holdCount == 1 && Cards.rank(card) == found[Cards.suit(card)]) {
                removeHeld();
                found[Cards.suit(card)]++;
                ok = true;
            }
        } else if (cur != hold) {
            int c = cur - 8;
            boolean fits = colN[c] == 0 || stackable(card, col[c][colN[c] - 1]);
            if (fits && holdCount <= maxMovable(colN[c] == 0)) {
                if (hold < 4) {
                    cell[hold] = -1;
                    col[c][colN[c]++] = card;
                } else {
                    int src = hold - 8, start = colN[src] - holdCount;
                    for (int k = 0; k < holdCount; k++) col[c][colN[c]++] = col[src][start + k];
                    colN[src] = start;
                }
                ok = true;
            }
        }
        if (ok) {
            moves++;
            hold = -1;
            depth = 1;
            Sfx.tone(72, 20);
            check();
            if (state == PLAY) autoT = 4;
        } else {
            Sfx.bad();
            if (cur == hold) hold = -1;
        }
    }

    protected void draw(Graphics g) {
        Cards.felt(g, W, H);
        int hud = Gfx.SMALL.getHeight() + 1;
        int gap = 2;
        int cw = (W - gap * 9) / 8;
        int ch = Cards.heightFor(cw);
        int topY = hud + 2, tabY = topY + ch + 5;
        int fu = Math.max(4, Gfx.SMALL_B.getHeight() + 1);
        for (int k = 0; k < 8; k++) {
            int x = gap + k * (cw + gap);
            if (k < 4) {
                if (cell[k] >= 0) Cards.draw(g, cell[k], x, topY - (hold == k ? 3 : 0), cw, ch, true);
                else Cards.drawSlot(g, x, topY, cw, ch);
            } else {
                int s = k - 4;
                if (found[s] > 0) Cards.draw(g, s * 13 + found[s] - 1, x, topY, cw, ch, true);
                else {
                    Cards.drawSlot(g, x, topY, cw, ch);
                    Cards.drawSuit(g, s, x + cw / 2 - 2, topY + ch / 2 - 3);
                }
            }
        }
        for (int c = 0; c < 8; c++) {
            int x = gap + c * (cw + gap);
            int n = colN[c];
            if (n == 0) {
                Cards.drawSlot(g, x, tabY, cw, ch);
                continue;
            }
            int step = fu;
            if (tabY + (n - 1) * step + ch > H - 2) step = Math.max(3, (H - 2 - tabY - ch) / Math.max(1, n - 1));
            for (int i = 0; i < n; i++) {
                boolean lifted = hold == c + 8 && i >= n - holdCount;
                Cards.draw(g, col[c][i], x, tabY + i * step - (lifted ? 3 : 0), cw, ch, true);
            }
        }
        if (state == PLAY || state == PAUSE) {
            int x, y, hh = ch;
            if (cur < 8) { x = gap + cur * (cw + gap); y = topY; }
            else {
                int c = cur - 8, n = colN[c];
                x = gap + c * (cw + gap);
                int step = fu;
                if (tabY + (n - 1) * step + ch > H - 2) step = Math.max(3, (H - 2 - tabY - ch) / Math.max(1, n - 1));
                int idx = Math.max(0, n - (hold >= 0 ? 1 : depth));
                y = tabY + idx * step;
                hh = ch + Math.max(0, n - 1 - idx) * step;
            }
            g.setColor(hold >= 0 ? 0xFFEB3B : 0x40C4FF);
            g.drawRect(x - 1, y - 1, cw + 1, hh + 1);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("Moves " + moves, 2, 0, Gfx.TL);
        g.setColor(0xBFE6CC);
        g.drawString(Gfx.time(frame, tickMs), W - 2, 0, Gfx.TR);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cw = Math.max(12, Math.min(w / 6, h * 3 / 5));
        int ch = Cards.heightFor(cw);
        int x0 = x + (w - cw * 4 - 6) / 2;
        for (int k = 0; k < 4; k++) {
            int r = (clock / 12 + k) % 13;
            Cards.draw(g, k * 13 + r, x0 + k * (cw + 2), y + (h - ch) / 2, cw, ch, true);
        }
    }
}
