package eightsshed;

import gamekit.Cards;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Eights Shed: be the first to get rid of all your cards. Match the top
 * card's suit or rank; eights are wild and let you name the next suit.
 */
public class EightsGame extends Game {
    private static final String[] SUIT = { "Spades", "Hearts", "Diamonds", "Clubs" };
    private final int[] deck = new int[52];
    private int deckN;
    private final int[][] hand = new int[4][52];
    private final int[] handN = new int[4];
    private int players, turn, topCard, suitNow, sel, wait, pickSuit = -1, drewThisTurn, passes;
    private String note = "";

    protected String name() { return "Eights Shed"; }

    protected String[] help() {
        return new String[] {
            "Get rid of all your cards before the others. On your turn play one card that matches the top card's suit or rank.",
            "Eights are wild: play one at any time and choose the suit the next player must follow.",
            "Can't play? Draw a card (#). If it fits you may play it, otherwise press # again to pass.",
            "- Controls",
            "4/6: choose card  5: play",
            "#: draw / pass",
        };
    }

    protected String[] modes() { return new String[] { "vs 1 CPU", "vs 2 CPUs", "vs 3 CPUs" }; }

    protected int accent() { return 0x43AA8B; }

    protected void newGame() {
        players = mode + 2;
        Cards.shuffle(deck);
        deckN = 52;
        int deal = players == 2 ? 7 : 5;
        for (int p = 0; p < players; p++) {
            handN[p] = 0;
            for (int k = 0; k < deal; k++) hand[p][handN[p]++] = deck[--deckN];
        }
        do {
            topCard = deck[--deckN];
        } while (Cards.rank(topCard) == 7 && deckN > 10);
        suitNow = Cards.suit(topCard);
        turn = 0;
        sel = 0;
        pickSuit = -1;
        drewThisTurn = 0;
        passes = 0;
        note = "Your turn";
    }

    private boolean playable(int c) {
        return Cards.rank(c) == 7 || Cards.suit(c) == suitNow || Cards.rank(c) == Cards.rank(topCard);
    }

    private int drawCard(int p) {
        if (deckN == 0) return -1;
        int c = deck[--deckN];
        hand[p][handN[p]++] = c;
        return c;
    }

    private void play(int p, int idx) {
        int c = hand[p][idx];
        hand[p][idx] = hand[p][--handN[p]];
        topCard = c;
        suitNow = Cards.suit(c);
        passes = 0;
        Sfx.tone(p == 0 ? 72 : 64, 25);
    }

    private void nextTurn() {
        if (handN[turn] == 0) {
            finish(turn);
            return;
        }
        turn = (turn + 1) % players;
        drewThisTurn = 0;
        wait = 0;
        note = turn == 0 ? "Your turn" : "CPU " + turn + "...";
    }

    private void finish(int winner) {
        int pts = 0;
        for (int p = 0; p < players; p++) {
            for (int k = 0; k < handN[p]; k++) {
                int r = Cards.rank(hand[p][k]);
                pts += r == 7 ? 50 : (r >= 9 ? 10 : r + 1);
            }
        }
        if (winner == 0) {
            score = pts;
            headline = "YOU SHED THEM ALL!";
            endGame(true);
        } else {
            score = 0;
            headline = "CPU " + winner + " WINS";
            endGame(false);
        }
    }

    protected void update() {
        if (turn != 0) {
            if (++wait < 18) return;
            cpuTurn(turn);
            return;
        }
        if (pickSuit >= 0) {
            if ((pressed & K_LEFT) != 0) pickSuit = (pickSuit + 3) % 4;
            if ((pressed & K_RIGHT) != 0) pickSuit = (pickSuit + 1) % 4;
            if ((pressed & K_FIRE) != 0) {
                suitNow = pickSuit;
                pickSuit = -1;
                note = "Suit is now " + SUIT[suitNow];
                nextTurn();
            }
            return;
        }
        int n = handN[0];
        if (sel >= n) sel = Math.max(0, n - 1);
        if ((pressed & K_LEFT) != 0 && n > 0) sel = (sel + n - 1) % n;
        if ((pressed & K_RIGHT) != 0 && n > 0) sel = (sel + 1) % n;
        if ((pressed & K_FIRE) != 0 && n > 0) {
            int c = hand[0][sel];
            if (!playable(c)) {
                Sfx.bad();
                note = "Match suit or rank";
                return;
            }
            play(0, sel);
            if (Cards.rank(c) == 7 && handN[0] > 0) {
                pickSuit = suitNow;
                note = "Choose a suit";
                return;
            }
            nextTurn();
            return;
        }
        if ((pressed & K_POUND) != 0) {
            if (drewThisTurn == 0) {
                int c = drawCard(0);
                drewThisTurn = 1;
                if (c < 0) {
                    note = "Deck empty - pass";
                } else {
                    sel = handN[0] - 1;
                    note = playable(c) ? "You can play it!" : "No luck - # to pass";
                    Sfx.click();
                }
            } else {
                passes++;
                if (passes >= players * 2 && deckN == 0) {
                    int best = 0;
                    for (int p = 1; p < players; p++) if (handN[p] < handN[best]) best = p;
                    finish(best);
                    return;
                }
                nextTurn();
            }
        }
    }

    private void cpuTurn(int p) {
        int bestIdx = -1, eightIdx = -1;
        int[] suitCount = new int[4];
        for (int k = 0; k < handN[p]; k++) suitCount[Cards.suit(hand[p][k])]++;
        for (int k = 0; k < handN[p]; k++) {
            int c = hand[p][k];
            if (!playable(c)) continue;
            if (Cards.rank(c) == 7) { eightIdx = k; continue; }
            if (bestIdx < 0 || suitCount[Cards.suit(c)] > suitCount[Cards.suit(hand[p][bestIdx])]) bestIdx = k;
        }
        int idx = bestIdx >= 0 ? bestIdx : eightIdx;
        if (idx < 0) {
            int c = drawCard(p);
            if (c >= 0 && playable(c)) idx = handN[p] - 1;
            else {
                passes++;
                note = "CPU " + p + (c < 0 ? " passes" : " draws");
                if (passes >= players * 2 && deckN == 0) {
                    int best = 0;
                    for (int q = 1; q < players; q++) if (handN[q] < handN[best]) best = q;
                    finish(best);
                    return;
                }
                nextTurn();
                return;
            }
        }
        int c = hand[p][idx];
        play(p, idx);
        if (Cards.rank(c) == 7) {
            int s = 0;
            for (int k = 1; k < 4; k++) if (suitCount[k] > suitCount[s]) s = k;
            suitNow = s;
            note = "CPU " + p + ": eight! " + SUIT[s];
        } else {
            note = "CPU " + p + " plays";
        }
        if (handN[p] == 1) note = "CPU " + p + ": last card!";
        nextTurn();
    }

    protected void draw(Graphics g) {
        Cards.felt(g, W, H);
        int hud = Gfx.SMALL.getHeight() + 2;
        int cw = Math.max(16, Math.min(W / 6, (H / 4) * 3 / 4));
        int ch = Cards.heightFor(cw);
        // opponents
        for (int p = 1; p < players; p++) {
            int x = (p - 1) * W / (players - 1) + 4, y = hud;
            g.setFont(Gfx.SMALL_B);
            g.setColor(turn == p ? 0xFFEB3B : 0xFFFFFF);
            g.drawString("CPU" + p + ": " + handN[p], x, y, Gfx.TL);
            int mini = Math.max(6, cw / 3);
            for (int k = 0; k < Math.min(handN[p], 8); k++) Cards.drawBack(g, x + k * 3, y + Gfx.SMALL.getHeight() + 2, mini, mini * 4 / 3);
        }
        // pile and deck
        int cy = H / 2 - ch / 2 - 4;
        Cards.draw(g, topCard, W / 2 + 2, cy, cw, ch, true);
        if (deckN > 0) Cards.drawBack(g, W / 2 - cw - 4, cy, cw, ch);
        else Cards.drawSlot(g, W / 2 - cw - 4, cy, cw, ch);
        g.setFont(Gfx.SMALL);
        g.setColor(0xFFFFFF);
        g.drawString(deckN + " left", W / 2 - cw / 2 - 4, cy + ch + 1, Gfx.TC);
        if (Cards.rank(topCard) == 7 || suitNow != Cards.suit(topCard)) {
            g.drawString("Suit:", W / 2 + cw + 6, cy + 2, Gfx.TL);
            Cards.drawSuit(g, suitNow, W / 2 + cw + 8, cy + 4 + Gfx.SMALL.getHeight());
        }
        // your hand
        int n = handN[0];
        int hy = H - ch - hud;
        int spread = n <= 1 ? cw : Math.min(cw + 2, (W - 4 - cw) / (n - 1));
        int width = cw + spread * Math.max(0, n - 1);
        int hx = (W - width) / 2;
        for (int k = 0; k < n; k++) {
            boolean s = k == sel && turn == 0 && pickSuit < 0;
            Cards.draw(g, hand[0][k], hx + k * spread, hy - (s ? 5 : 0), cw, ch, true);
            if (s && !playable(hand[0][k])) {
                g.setColor(0xFF5252);
                g.drawRect(hx + k * spread, hy - 5, cw - 1, ch - 1);
            }
        }
        if (pickSuit >= 0) {
            int bw = W - 20, bh = ch / 2 + Gfx.SMALL.getHeight() + 10;
            Gfx.panel(g, 10, H / 2 - bh / 2, bw, bh, 0x0E3B1F, 0xFFEB3B);
            Gfx.text(g, "Choose suit: " + SUIT[pickSuit], W / 2, H / 2 - bh / 2 + 3, Gfx.TC, Gfx.SMALL_B, 0xFFEB3B);
            for (int s = 0; s < 4; s++) {
                int x = 10 + bw * (2 * s + 1) / 8 - 3;
                if (s == pickSuit) {
                    g.setColor(0xFFFFFF);
                    g.fillRect(x - 3, H / 2 + 1, 13, 12);
                }
                Cards.drawSuit(g, s, x, H / 2 + 4);
            }
        }
        Gfx.text(g, note, W / 2, H - hud + 1, Gfx.TC, Gfx.SMALL_B, 0xFFFFFF);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cw = Math.max(12, Math.min(w / 6, h * 3 / 5));
        int ch = Cards.heightFor(cw);
        for (int k = 0; k < 4; k++) {
            int off = ((clock / 15) + k) % 4;
            Cards.draw(g, k * 13 + 7, x + w / 2 - cw * 2 + k * cw - off, y + (h - ch) / 2 + off, cw, ch, true);
        }
    }
}
