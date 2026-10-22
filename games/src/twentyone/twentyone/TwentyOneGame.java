package twentyone;

import gamekit.Cards;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Twenty-One: beat the dealer without going over 21. Start with 100 chips, reach 1000. */
public class TwentyOneGame extends Game {
    private static final int BET = 0, PLAYER = 1, DEALER = 2, RESULT = 3;
    private static final int[] TITLE_CARDS = { 0, 12, 22, 49 };
    private static final String[] ACTIONS = { "Hit", "Stand", "Double" };

    private final int[] shoe = new int[208];
    private int shoePos;
    private final int[] ph = new int[12], dh = new int[12];
    private int np, nd;
    private int chips, bet = 10, phase, sel, peak, dealerWait;
    private boolean doubled;
    private String result = "";
    private int resultColor;

    protected String name() { return "Twenty-One"; }

    protected String[] help() {
        return new String[] {
            "Get closer to 21 than the dealer without going over. Number cards count their value, J/Q/K count 10, an Ace counts 1 or 11.",
            "Hit takes another card, Stand ends your turn, Double doubles your bet for exactly one more card. The dealer must draw to 17.",
            "A two-card 21 (Blackjack) pays 3 to 2. You start with 100 chips: reach 1000 to win, run out and it's over.",
            "- Controls",
            "Betting: 4/6 change bet, 5 deal",
            "Playing: 4/6 choose, 5 confirm",
            "Shortcuts: 1 hit, 3 stand, 9 double",
        };
    }

    protected int accent() { return 0x43AA8B; }

    protected String formatScore(int s) { return s + " chips"; }

    protected void newGame() {
        Cards.shuffle(shoe);
        shoePos = 0;
        chips = 100;
        peak = 100;
        bet = 10;
        phase = BET;
        np = nd = 0;
    }

    private int draw() {
        if (shoePos >= shoe.length) {
            Cards.shuffle(shoe);
            shoePos = 0;
        }
        return shoe[shoePos++];
    }

    private static int total(int[] h, int n) {
        int t = 0, aces = 0;
        for (int i = 0; i < n; i++) {
            int r = Cards.rank(h[i]);
            if (r == 0) { aces++; t += 11; } else t += r >= 9 ? 10 : r + 1;
        }
        while (t > 21 && aces > 0) { t -= 10; aces--; }
        return t;
    }

    private void deal() {
        if (shoePos > shoe.length - 52) {
            Cards.shuffle(shoe);
            shoePos = 0;
        }
        chips -= bet;
        doubled = false;
        np = nd = 0;
        ph[np++] = draw();
        dh[nd++] = draw();
        ph[np++] = draw();
        dh[nd++] = draw();
        sel = 0;
        Sfx.click();
        boolean pbj = total(ph, 2) == 21, dbj = total(dh, 2) == 21;
        if (pbj || dbj) {
            if (pbj && dbj) settle(bet, "Push - both Blackjack", 0xFFFFFF);
            else if (pbj) settle(bet + bet * 3 / 2, "BLACKJACK! +" + (bet * 3 / 2), 0xFFD54F);
            else settle(0, "Dealer Blackjack", 0xFF8A80);
            return;
        }
        phase = PLAYER;
    }

    private void settle(int payout, String msg, int color) {
        chips += payout;
        result = msg;
        resultColor = color;
        phase = RESULT;
        if (chips > peak) peak = chips;
        if (payout > bet) Sfx.good(); else if (payout == 0) Sfx.bad();
    }

    private void finishRound() {
        int p = total(ph, np), d = total(dh, nd);
        if (p > 21) settle(0, "Bust! -" + bet, 0xFF8A80);
        else if (d > 21) settle(bet * 2, "Dealer busts! +" + bet, 0x9CFF9C);
        else if (p > d) settle(bet * 2, "You win! +" + bet, 0x9CFF9C);
        else if (p == d) settle(bet, "Push", 0xFFFFFF);
        else settle(0, "Dealer wins -" + bet, 0xFF8A80);
    }

    protected void update() {
        switch (phase) {
            case BET:
                if (chips < 5) {
                    score = peak;
                    headline = "OUT OF CHIPS";
                    endGame(false);
                    return;
                }
                if (bet > chips) bet = chips - chips % 5;
                if ((pressed & K_RIGHT) != 0 && bet + 5 <= Math.min(chips, 200)) { bet += 5; Sfx.click(); }
                if ((pressed & K_LEFT) != 0 && bet > 5) { bet -= 5; Sfx.click(); }
                if ((pressed & K_UP) != 0) bet = Math.min(Math.min(chips, 200), bet * 2 - bet % 5);
                if ((pressed & K_DOWN) != 0) bet = Math.max(5, (bet / 2) - (bet / 2) % 5);
                if ((pressed & K_FIRE) != 0) deal();
                break;
            case PLAYER: {
                boolean canDouble = np == 2 && chips >= bet;
                int n = canDouble ? 3 : 2;
                if (sel >= n) sel = 0;
                if ((pressed & K_LEFT) != 0) sel = (sel + n - 1) % n;
                if ((pressed & K_RIGHT) != 0) sel = (sel + 1) % n;
                int act = -1;
                if ((pressed & K_FIRE) != 0) act = sel;
                if (digit(1)) act = 0;
                if (digit(3)) act = 1;
                if (digit(9) && canDouble) act = 2;
                if (act == 0) {
                    ph[np++] = draw();
                    Sfx.click();
                    if (total(ph, np) > 21) finishRound();
                    else if (total(ph, np) == 21 || np == 11) startDealer();
                } else if (act == 1) {
                    startDealer();
                } else if (act == 2) {
                    chips -= bet;
                    bet *= 2;
                    doubled = true;
                    ph[np++] = draw();
                    Sfx.click();
                    if (total(ph, np) > 21) finishRound();
                    else startDealer();
                }
                break;
            }
            case DEALER:
                if (--dealerWait > 0) break;
                int d = total(dh, nd);
                if (d < 17) { // dealer stands on every 17
                    dh[nd++] = draw();
                    Sfx.click();
                    dealerWait = 12;
                } else {
                    finishRound();
                }
                break;
            default:
                if ((pressed & K_FIRE) != 0) {
                    if (doubled) bet /= 2;
                    if (chips >= 1000) {
                        score = chips;
                        headline = "HIGH ROLLER!";
                        endGame(true);
                        return;
                    }
                    phase = BET;
                }
                break;
        }
        score = peak;
    }

    private void startDealer() {
        phase = DEALER;
        dealerWait = 14;
    }

    private void drawHand(Graphics g, int[] h, int n, int y, int cw, int chh, boolean hideHole) {
        int spread = Math.min(cw + 3, (W - 8 - cw) / Math.max(1, n - 1));
        int width = cw + spread * (n - 1);
        int x0 = (W - width) / 2;
        for (int i = 0; i < n; i++) Cards.draw(g, h[i], x0 + i * spread, y, cw, chh, !(hideHole && i == 1));
    }

    protected void draw(Graphics g) {
        Cards.felt(g, W, H);
        int hud = Gfx.SMALL.getHeight() + 2;
        int cw = Math.max(16, Math.min(W / 5, (H - hud * 4) * 3 / 9));
        int chh = Cards.heightFor(cw);
        int dy = hud + Gfx.SMALL.getHeight() + 4;
        int py = H - hud * 2 - chh - Gfx.SMALL.getHeight() - 6;
        g.setFont(Gfx.SMALL_B);
        g.setColor(0x0E3B1F);
        g.fillRect(0, 0, W, hud);
        g.setColor(0xFFD54F);
        g.drawString("Chips " + chips, 2, 1, Gfx.TL);
        g.setColor(0xFFFFFF);
        g.drawString("Bet " + bet, W - 2, 1, Gfx.TR);
        if (phase == BET) {
            Gfx.text(g, "Place your bet", W / 2, H / 3, Gfx.TC, Gfx.MEDIUM, 0xFFFFFF);
            Gfx.text(g, "< " + bet + " >", W / 2, H / 3 + Gfx.MEDIUM.getHeight() + 4, Gfx.TC, Gfx.LARGE, 0xFFD54F);
            Gfx.text(g, "4/6 +-5  2/8 x2 /2", W / 2, H / 2 + 14, Gfx.TC, Gfx.SMALL, 0xBFE6CC);
            Gfx.text(g, "5: deal", W / 2, H / 2 + 26, Gfx.TC, Gfx.SMALL_B, 0xFFFFFF);
            return;
        }
        boolean hide = phase == PLAYER;
        Gfx.text(g, "Dealer" + (hide ? "" : ": " + total(dh, nd)), W / 2, hud + 1, Gfx.TC, Gfx.SMALL_B, 0xDDEEDD);
        drawHand(g, dh, nd, dy, cw, chh, hide);
        Gfx.text(g, "You: " + total(ph, np) + (doubled ? " (x2)" : ""), W / 2, py - Gfx.SMALL.getHeight() - 2, Gfx.TC, Gfx.SMALL_B, 0xFFFFFF);
        drawHand(g, ph, np, py, cw, chh, false);
        int by = H - hud - 3;
        if (phase == PLAYER) {
            boolean canDouble = np == 2 && chips >= bet;
            int n = canDouble ? 3 : 2;
            int bw = (W - 8) / n;
            for (int i = 0; i < n; i++) {
                int x = 4 + i * bw;
                Gfx.panel(g, x + 1, by, bw - 2, hud + 1, i == sel ? 0xFFD54F : 0x0E3B1F, 0xBFE6CC);
                Gfx.text(g, ACTIONS[i], x + bw / 2, by + 1, Gfx.TC, Gfx.SMALL_B, i == sel ? 0x1A1A1A : 0xFFFFFF);
            }
        } else if (phase == RESULT) {
            Gfx.panel(g, 4, H / 2 - hud, W - 8, hud * 2, 0x0E3B1F, resultColor);
            Gfx.text(g, result, W / 2, H / 2 - hud / 2 - 1, Gfx.TC, Gfx.SMALL_B, resultColor);
            Gfx.text(g, "5: next hand", W / 2, by + 1, Gfx.TC, Gfx.SMALL, 0xBFE6CC);
        }
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cw = Math.max(14, Math.min(w / 5, h * 3 / 5));
        int ch = Cards.heightFor(cw);
        int x0 = x + (w - (cw * 2 + 30)) / 2;
        for (int i = 0; i < 2; i++) {
            int lift = ((clock / 8) % 2 == i) ? 3 : 0;
            Cards.draw(g, TITLE_CARDS[i + ((clock / 40) & 1) * 2], x0 + i * (cw / 2 + 4), y + (h - ch) / 2 - lift, cw, ch, true);
        }
        Gfx.text(g, "21", x0 + cw * 2 + 6, y + (h - Gfx.LARGE.getHeight()) / 2, Gfx.TL, Gfx.LARGE, 0xFFD54F);
    }
}
