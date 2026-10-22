package klondike;

import gamekit.Cards;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Klondike solitaire. The cursor moves over the top row (stock, waste,
 * foundations) and the seven tableau columns; up/down inside a column
 * selects how many face-up cards to pick up.
 */
public class KlondikeGame extends Game {
    private static final int STOCK = 0, WASTE = 1; // slots 2..5 foundations, 6..12 tableau

    private final int[][] tab = new int[7][20];
    private final int[] tabN = new int[7], tabDown = new int[7];
    private final int[] stock = new int[52], waste = new int[52];
    private int stockN, wasteN;
    private final int[] found = new int[4]; // cards on each suit's foundation
    private final int[] deck = new int[52];
    private int cur = 6, depth = 1, holdSlot = -1, holdCount, moves, autoTimer;

    protected String name() { return "Klondike"; }

    protected String[] help() {
        return new String[] {
            "Build all four suits up from Ace to King on the foundations (top right).",
            "In the seven columns, stack cards downwards in alternating colours. Only a King may fill an empty column. Turn cards from the stock (top left) onto the waste pile when you are stuck.",
            "Pick up with 5, move the cursor to where the cards should go and press 5 again. In a column, press 2 (up) to pick up more than one card of a run. # sends the card under the cursor straight to its foundation.",
            "- Controls",
            "4/6: move  2/8: rows / run size",
            "5: pick up / drop / draw",
            "#: to foundation  0: cancel",
        };
    }

    protected String[] modes() { return new String[] { "Draw 1", "Draw 3" }; }

    protected boolean lowerIsBetter() { return false; }

    protected int accent() { return 0x43AA8B; }

    protected void newGame() {
        Cards.shuffle(deck);
        int k = 0;
        for (int c = 0; c < 7; c++) {
            tabN[c] = c + 1;
            tabDown[c] = c;
            for (int i = 0; i <= c; i++) tab[c][i] = deck[k++];
        }
        stockN = 0;
        while (k < 52) stock[stockN++] = deck[k++];
        wasteN = 0;
        for (int s = 0; s < 4; s++) found[s] = 0;
        cur = 6;
        depth = 1;
        holdSlot = -1;
        moves = 0;
        autoTimer = 0;
    }

    private static boolean red(int c) { return Cards.red(c); }

    private int topOf(int slot) {
        if (slot == WASTE) return wasteN > 0 ? waste[wasteN - 1] : -1;
        if (slot >= 6) {
            int c = slot - 6;
            return tabN[c] > 0 ? tab[c][tabN[c] - 1] : -1;
        }
        return -1;
    }

    /** First card of the selection being moved. */
    private int heldCard() {
        if (holdSlot == WASTE) return waste[wasteN - 1];
        int c = holdSlot - 6;
        return tab[c][tabN[c] - holdCount];
    }

    private boolean canTableau(int card, int col) {
        if (tabN[col] == 0) return Cards.rank(card) == 12;
        int t = tab[col][tabN[col] - 1];
        return red(t) != red(card) && Cards.rank(t) == Cards.rank(card) + 1;
    }

    private boolean canFoundation(int card) {
        return Cards.rank(card) == found[Cards.suit(card)];
    }

    private void removeHeld() {
        if (holdSlot == WASTE) wasteN--;
        else {
            int c = holdSlot - 6;
            tabN[c] -= holdCount;
            if (tabN[c] > 0 && tabDown[c] >= tabN[c]) tabDown[c] = tabN[c] - 1;
        }
    }

    private boolean toFoundation(int slot) {
        int card = topOf(slot);
        if (card < 0 || !canFoundation(card)) return false;
        if (slot == WASTE) wasteN--;
        else {
            int c = slot - 6;
            tabN[c]--;
            if (tabN[c] > 0 && tabDown[c] >= tabN[c]) tabDown[c] = tabN[c] - 1;
        }
        found[Cards.suit(card)]++;
        score += 10;
        moves++;
        Sfx.tone(76 + Cards.rank(card) / 2, 30);
        checkWin();
        return true;
    }

    private void checkWin() {
        if (found[0] + found[1] + found[2] + found[3] == 52) {
            score += Math.max(0, 1000 - frame / 20);
            headline = "SOLVED!";
            endGame(true);
        }
    }

    private void drawStock() {
        if (stockN == 0) {
            while (wasteN > 0) stock[stockN++] = waste[--wasteN];
            Sfx.tone(50, 40);
            return;
        }
        int n = mode == 1 ? 3 : 1;
        for (int i = 0; i < n && stockN > 0; i++) waste[wasteN++] = stock[--stockN];
        Sfx.click();
    }

    private int faceUp(int col) {
        return tabN[col] - tabDown[col];
    }

    private boolean autoPossible() {
        if (stockN > 0 || wasteN > 0) return false;
        for (int c = 0; c < 7; c++) if (tabDown[c] > 0) return false;
        return true;
    }

    protected void update() {
        if (autoTimer > 0) {
            if (--autoTimer == 0) {
                for (int c = 0; c < 7; c++) {
                    if (toFoundation(6 + c)) {
                        if (state == PLAY) autoTimer = 3;
                        return;
                    }
                }
            }
            return;
        }
        boolean top = cur < 6;
        if ((pressed & K_LEFT) != 0) {
            if (top) cur = cur == 2 ? WASTE : (cur == WASTE ? STOCK : (cur == STOCK ? 5 : cur - 1));
            else cur = cur == 6 ? 12 : cur - 1;
            depth = 1;
        }
        if ((pressed & K_RIGHT) != 0) {
            if (top) cur = cur == STOCK ? WASTE : (cur == WASTE ? 2 : (cur == 5 ? STOCK : cur + 1));
            else cur = cur == 12 ? 6 : cur + 1;
            depth = 1;
        }
        if ((pressed & K_UP) != 0) {
            if (!top && holdSlot < 0 && depth < faceUp(cur - 6)) depth++;
            else if (!top) {
                int col = cur - 6;
                cur = col == 0 ? STOCK : (col == 1 ? WASTE : (col == 2 ? WASTE : col - 1));
                depth = 1;
            }
        }
        if ((pressed & K_DOWN) != 0) {
            if (top) {
                cur = cur == STOCK ? 6 : (cur == WASTE ? 7 : cur + 7); // foundations sit above columns 4-7
                depth = 1;
            } else if (depth > 1) depth--;
        }
        if (digit(0) && holdSlot >= 0) {
            holdSlot = -1;
            Sfx.click();
        }
        if ((pressed & K_POUND) != 0 && holdSlot < 0) {
            if (!toFoundation(cur)) Sfx.bad();
            else if (state == PLAY && autoPossible()) autoTimer = 4;
            return;
        }
        if ((pressed & K_FIRE) == 0) return;
        if (holdSlot < 0) {
            if (cur == STOCK) {
                drawStock();
                return;
            }
            if (cur == WASTE && wasteN > 0) {
                holdSlot = WASTE;
                holdCount = 1;
                Sfx.click();
            } else if (cur >= 6 && tabN[cur - 6] > 0) {
                holdSlot = cur;
                holdCount = Math.min(depth, faceUp(cur - 6));
                Sfx.click();
            }
            return;
        }
        // drop
        int card = heldCard();
        boolean ok = false;
        if (cur >= 2 && cur <= 5) {
            if (holdCount == 1 && canFoundation(card)) {
                removeHeld();
                found[Cards.suit(card)]++;
                score += 10;
                ok = true;
            }
        } else if (cur >= 6 && cur != holdSlot && canTableau(card, cur - 6)) {
            int dst = cur - 6;
            int srcStart;
            if (holdSlot == WASTE) {
                tab[dst][tabN[dst]++] = card;
                wasteN--;
                score += 5;
            } else {
                int src = holdSlot - 6;
                srcStart = tabN[src] - holdCount;
                for (int i = 0; i < holdCount; i++) tab[dst][tabN[dst]++] = tab[src][srcStart + i];
                tabN[src] = srcStart;
                if (tabN[src] > 0 && tabDown[src] >= tabN[src]) {
                    tabDown[src] = tabN[src] - 1;
                    score += 5;
                }
            }
            ok = true;
        }
        if (ok) {
            moves++;
            holdSlot = -1;
            depth = 1;
            Sfx.tone(72, 25);
            checkWin();
            if (state == PLAY && autoPossible()) autoTimer = 4;
        } else {
            Sfx.bad();
            if (cur == holdSlot) holdSlot = -1;
        }
    }

    protected void draw(Graphics g) {
        Cards.felt(g, W, H);
        int hud = Gfx.SMALL.getHeight() + 1;
        int gap = 2;
        int cw = (W - gap * 8) / 7;
        int ch = Cards.heightFor(cw);
        int topY = hud + 2;
        int tabY = topY + ch + 4;
        int fd = Math.max(2, ch / 9);
        int fu = Math.max(4, Gfx.SMALL_B.getHeight() + 2);
        // top row
        int sx = gap;
        if (stockN > 0) Cards.drawBack(g, sx, topY, cw, ch);
        else {
            Cards.drawSlot(g, sx, topY, cw, ch);
            g.setColor(0x2E6B3F);
            Gfx.ring(g, sx + cw / 2, topY + ch / 2, cw / 4);
        }
        int wx = gap * 2 + cw;
        if (wasteN > 0) {
            int show = mode == 1 ? Math.min(3, wasteN) : 1;
            int step = Math.max(3, cw / 4);
            for (int i = 0; i < show; i++) {
                int card = waste[wasteN - show + i];
                boolean lifted = holdSlot == WASTE && i == show - 1;
                Cards.draw(g, card, wx + i * step, topY - (lifted ? 3 : 0), cw, ch, true);
            }
        } else Cards.drawSlot(g, wx, topY, cw, ch);
        for (int s = 0; s < 4; s++) {
            int fx = gap + (3 + s) * (cw + gap);
            if (found[s] > 0) Cards.draw(g, s * 13 + found[s] - 1, fx, topY, cw, ch, true);
            else {
                Cards.drawSlot(g, fx, topY, cw, ch);
                Cards.drawSuit(g, s, fx + cw / 2 - 2, topY + ch / 2 - 3);
            }
        }
        // tableau
        for (int c = 0; c < 7; c++) {
            int x = gap + c * (cw + gap);
            int n = tabN[c];
            if (n == 0) {
                Cards.drawSlot(g, x, tabY, cw, ch);
                continue;
            }
            int up = n - tabDown[c];
            int need = tabDown[c] * fd + (up - 1) * fu + ch;
            int avail = H - tabY - 2;
            int fuC = fu;
            if (need > avail && up > 1) fuC = Math.max(3, (avail - ch - tabDown[c] * fd) / (up - 1));
            int y = tabY;
            for (int i = 0; i < n; i++) {
                boolean faceUp = i >= tabDown[c];
                boolean lifted = holdSlot == 6 + c && i >= n - holdCount;
                Cards.draw(g, tab[c][i], x, y - (lifted ? 3 : 0), cw, ch, faceUp);
                y += faceUp ? fuC : fd;
            }
        }
        // cursor
        if (state == PLAY || state == PAUSE) {
            int x, y, hgt = ch;
            if (cur == STOCK) { x = gap; y = topY; }
            else if (cur == WASTE) { x = wx; y = topY; }
            else if (cur <= 5) { x = gap + (cur + 1) * (cw + gap); y = topY; }
            else {
                int c = cur - 6;
                x = gap + c * (cw + gap);
                int n = tabN[c];
                int up = n - tabDown[c];
                int need = tabDown[c] * fd + (up - 1) * fu + ch;
                int fuC = fu;
                if (need > H - tabY - 2 && up > 1) fuC = Math.max(3, (H - tabY - 2 - ch - tabDown[c] * fd) / (up - 1));
                int idx = Math.max(0, n - (holdSlot >= 0 ? 1 : depth));
                y = tabY;
                for (int i = 0; i < idx; i++) y += i >= tabDown[c] ? fuC : fd;
                hgt = ch + (n > 0 ? (n - 1 - idx) * fuC : 0);
            }
            g.setColor(holdSlot >= 0 ? 0xFFEB3B : 0x40C4FF);
            g.drawRect(x - 1, y - 1, cw + 1, hgt + 1);
            g.drawRect(x - 2, y - 2, cw + 3, hgt + 3);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("Score " + score, 2, 0, Gfx.TL);
        g.setColor(0xBFE6CC);
        g.drawString(Gfx.time(frame, tickMs), W - 2, 0, Gfx.TR);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cw = Math.max(14, Math.min(w / 6, h * 3 / 5));
        int ch = Cards.heightFor(cw);
        int x0 = x + (w - cw * 4) / 2;
        for (int s = 0; s < 4; s++) {
            int r = (clock / 10 + s * 3) % 13;
            Cards.draw(g, s * 13 + r, x0 + s * cw, y + (h - ch) / 2 + (s % 2) * 3, cw - 2, ch, true);
        }
    }
}
