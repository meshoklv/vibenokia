package drawpoker;

import gamekit.Cards;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Draw Poker: jacks-or-better video poker. Hold cards with keys 1-5 and draw once. */
public class PokerGame extends Game {
    private static final String[] HANDS = { "Nothing", "Jacks or Better", "Two Pair", "Three of a Kind", "Straight", "Flush",
            "Full House", "Four of a Kind", "Straight Flush", "Royal Flush" };
    private static final int[] PAY = { 0, 1, 2, 3, 4, 6, 9, 25, 50, 250 };
    private static final int BET = 0, HOLD = 1, RESULT = 2;

    private final int[] deck = new int[52];
    private int deckPos;
    private final int[] hand = new int[5];
    private final boolean[] held2 = new boolean[5];
    private int credits, bet = 1, phase, cur, result, won, peak;

    protected String name() { return "Draw Poker"; }

    protected String[] help() {
        return new String[] {
            "Video poker, jacks or better. Bet 1 to 5 credits and you are dealt five cards. Choose which to hold, then draw replacements for the rest - once.",
            "Your final hand is paid from the table: a pair of jacks or better returns your bet, up to 250x for a royal flush (4000 credits on a 5-credit royal!).",
            "Start with 100 credits. Reach 1000 to break the bank.",
            "- Controls",
            "Bet: 4/6 change, 5 deal",
            "Hold: keys 1-5 toggle cards",
            "0 or #: draw",
        };
    }

    protected String formatScore(int s) { return s + " credits"; }

    protected int accent() { return 0xFFC107; }

    protected void newGame() {
        credits = 100;
        peak = 100;
        bet = 1;
        phase = BET;
    }

    private void deal() {
        credits -= bet;
        Cards.shuffle(deck);
        deckPos = 0;
        for (int i = 0; i < 5; i++) {
            hand[i] = deck[deckPos++];
            held2[i] = false;
        }
        phase = HOLD;
        cur = 0;
        result = evaluate();
        Sfx.click();
    }

    private void draw() {
        for (int i = 0; i < 5; i++) if (!held2[i]) hand[i] = deck[deckPos++];
        result = evaluate();
        won = PAY[result] * bet;
        if (result == 9 && bet == 5) won = 4000;
        credits += won;
        if (credits > peak) peak = credits;
        phase = RESULT;
        if (result >= 4) Sfx.win(); else if (won > 0) Sfx.good(); else Sfx.tone(48, 60);
    }

    private int evaluate() {
        int[] cnt = new int[13];
        int[] suits = new int[4];
        for (int i = 0; i < 5; i++) {
            cnt[Cards.rank(hand[i])]++;
            suits[Cards.suit(hand[i])]++;
        }
        boolean flush = false;
        for (int s = 0; s < 4; s++) if (suits[s] == 5) flush = true;
        int pairs = 0, three = 0, four = 0, highPair = 0;
        for (int r = 0; r < 13; r++) {
            if (cnt[r] == 2) {
                pairs++;
                if (r == 0 || r >= 10) highPair = 1;
            }
            if (cnt[r] == 3) three++;
            if (cnt[r] == 4) four++;
        }
        boolean straight = false;
        boolean royal = cnt[0] == 1 && cnt[9] == 1 && cnt[10] == 1 && cnt[11] == 1 && cnt[12] == 1;
        if (royal) straight = true;
        for (int r = 0; r <= 8 && !straight; r++) {
            boolean run = true;
            for (int k = 0; k < 5; k++) if (cnt[r + k] != 1) run = false;
            if (run) straight = true;
        }
        if (straight && flush) return royal ? 9 : 8;
        if (four > 0) return 7;
        if (three > 0 && pairs > 0) return 6;
        if (flush) return 5;
        if (straight) return 4;
        if (three > 0) return 3;
        if (pairs == 2) return 2;
        if (pairs == 1 && highPair == 1) return 1;
        return 0;
    }

    protected void update() {
        switch (phase) {
            case BET:
                if (credits <= 0) {
                    score = peak;
                    headline = "BROKE";
                    endGame(false);
                    return;
                }
                if ((pressed & K_RIGHT) != 0 && bet < Math.min(5, credits)) { bet++; Sfx.click(); }
                if ((pressed & K_LEFT) != 0 && bet > 1) { bet--; Sfx.click(); }
                if (bet > credits) bet = credits;
                if ((pressed & K_FIRE) != 0) deal();
                break;
            case HOLD: {
                int d = digitPressed();
                if (d >= 1 && d <= 5) {
                    held2[d - 1] = !held2[d - 1];
                    cur = d - 1;
                    Sfx.click();
                } else if (d == 0 || (pressed & K_POUND) != 0) {
                    draw();
                } else if (d < 0) {
                    if ((pressed & K_LEFT) != 0) cur = (cur + 4) % 5;
                    if ((pressed & K_RIGHT) != 0) cur = (cur + 1) % 5;
                    if ((pressed & K_FIRE) != 0) {
                        held2[cur] = !held2[cur];
                        Sfx.click();
                    }
                }
                break;
            }
            default:
                if ((pressed & K_FIRE) != 0) {
                    if (credits >= 1000) {
                        score = credits;
                        headline = "BANK BROKEN!";
                        endGame(true);
                        return;
                    }
                    phase = BET;
                }
                break;
        }
        score = peak;
    }

    protected void draw(Graphics g) {
        g.setColor(0x0D1B4C);
        g.fillRect(0, 0, W, H);
        int hud = Gfx.SMALL.getHeight() + 2;
        // pay table
        int lh = Gfx.SMALL.getHeight();
        int rows = Math.min(9, (H / 3) / lh);
        g.setFont(Gfx.SMALL);
        for (int k = 0; k < rows; k++) {
            int hnd = 9 - k;
            int y = hud + k * lh;
            boolean hit = phase != BET && result == hnd && (phase == RESULT || hnd > 0);
            g.setColor(hit ? 0xFFEB3B : 0x90CAF9);
            String nm = HANDS[hnd];
            if (W < 160 && nm.length() > 13) nm = nm.substring(0, 13);
            g.drawString(nm, 3, y, Gfx.TL);
            g.drawString(String.valueOf(hnd == 9 && bet == 5 ? 4000 : PAY[hnd] * bet), W - 3, y, Gfx.TR);
        }
        int cw = Math.max(16, Math.min((W - 12) / 5, (H - hud - rows * lh) * 3 / 6));
        int ch = Cards.heightFor(cw);
        int cy = hud + rows * lh + 6;
        int cx0 = (W - (cw + 2) * 5) / 2;
        for (int i = 0; i < 5; i++) {
            int x = cx0 + i * (cw + 2);
            if (phase == BET) Cards.drawBack(g, x, cy, cw, ch);
            else Cards.draw(g, hand[i], x, cy - (held2[i] ? 3 : 0), cw, ch, true);
            g.setFont(Gfx.SMALL_B);
            if (phase == HOLD && held2[i]) {
                g.setColor(0xFFEB3B);
                g.drawString("HOLD", x + cw / 2, cy + ch + 1, Gfx.TC);
            } else {
                g.setColor(0x5C6BC0);
                g.drawString(String.valueOf(i + 1), x + cw / 2, cy + ch + 1, Gfx.TC);
            }
            if (phase == HOLD && i == cur) {
                g.setColor(0x40C4FF);
                g.drawRect(x - 1, cy - 4, cw + 1, ch + 4);
            }
        }
        g.setColor(0x050C26);
        g.fillRect(0, 0, W, hud);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFC107);
        g.drawString("Credits " + credits, 2, 1, Gfx.TL);
        g.setColor(0xFFFFFF);
        g.drawString("Bet " + bet, W - 2, 1, Gfx.TR);
        String hint;
        if (phase == BET) hint = "4/6 bet  5 deal";
        else if (phase == HOLD) hint = "1-5 hold  0 draw";
        else hint = (won > 0 ? HANDS[result] + " +" + won : "No win") + "  5";
        Gfx.hint(g, hint, W, H);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cw = Math.max(12, Math.min(w / 6, h * 3 / 5));
        int ch = Cards.heightFor(cw);
        int x0 = x + (w - cw * 5 - 8) / 2;
        int[] royal = { 9, 10, 11, 12, 0 };
        for (int i = 0; i < 5; i++) {
            boolean up = (clock / 6) % 10 > i;
            Cards.draw(g, 13 + royal[i], x0 + i * (cw + 2), y + (h - ch) / 2, cw, ch, up);
        }
    }
}
