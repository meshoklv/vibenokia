package gamekit;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Playing cards for the card games. A card is an int 0..51:
 * rank = card % 13 (0 = Ace .. 12 = King), suit = card / 13
 * (0 spades, 1 hearts, 2 diamonds, 3 clubs). Faces are drawn with
 * primitives and tiny pixel suit symbols, so they scale to any size.
 */
public final class Cards {
    public static final String RANKS = "A23456789TJQK";
    private static final String[][] SUIT_ART = {
        { "..1..", ".111.", "11111", "11111", "..1..", ".111." },
        { ".1.1.", "11111", "11111", ".111.", "..1..", "....." },
        { "..1..", ".111.", "11111", ".111.", "..1..", "....." },
        { "..1..", ".111.", "11111", "1.1.1", "..1..", ".111." },
    };
    private static final Image[][] PIPS = new Image[5][];

    private Cards() {}

    public static int rank(int c) { return c % 13; }
    public static int suit(int c) { return c / 13; }
    public static boolean red(int c) { int s = c / 13; return s == 1 || s == 2; }

    public static String rankName(int c) {
        int r = c % 13;
        return r == 9 ? "10" : String.valueOf(RANKS.charAt(r));
    }

    /** Fill deck with 0..n-1 and shuffle it. */
    public static void shuffle(int[] deck) {
        for (int i = 0; i < deck.length; i++) deck[i] = i % 52;
        Rnd.shuffle(deck);
    }

    /** Suit symbol image at a pixel scale (1..4), built once and cached. */
    private static Image pip(int suit, int scale) {
        if (scale > 4) scale = 4;
        if (PIPS[scale] == null) {
            Image[] set = new Image[4];
            for (int s = 0; s < 4; s++) {
                set[s] = Gfx.sprite(SUIT_ART[s], new int[] { 0, s == 1 || s == 2 ? 0xD32F2F : 0x1A1A1A }, scale);
            }
            PIPS[scale] = set;
        }
        return PIPS[scale][suit];
    }

    /** Small suit symbol (5x6 pixels) for status text. */
    public static void drawSuit(Graphics g, int suit, int x, int y) {
        g.drawImage(pip(suit, 1), x, y, Graphics.TOP | Graphics.LEFT);
    }

    /** Recommended card height for a given width. */
    public static int heightFor(int w) {
        return w * 4 / 3;
    }

    /** Draw a card face (or back) in the box x, y, w, h. */
    public static void draw(Graphics g, int card, int x, int y, int w, int h, boolean faceUp) {
        if (!faceUp) {
            drawBack(g, x, y, w, h);
            return;
        }
        g.setColor(0xFDFDF8);
        g.fillRoundRect(x, y, w, h, 4, 4);
        g.setColor(0x6B6B6B);
        g.drawRoundRect(x, y, w - 1, h - 1, 4, 4);
        boolean red = red(card);
        Font f = w >= 26 ? Gfx.MEDIUM : Gfx.SMALL_B;
        g.setFont(f);
        g.setColor(red ? 0xD32F2F : 0x1A1A1A);
        String rn = rankName(card);
        g.drawString(rn, x + 2, y + 1, Graphics.TOP | Graphics.LEFT);
        int scale = w >= 34 ? 2 : 1;
        Image p = pip(suit(card), scale);
        if (h >= f.getHeight() + p.getHeight() + 3) {
            g.drawImage(p, x + 2, y + f.getHeight() + 1, Graphics.TOP | Graphics.LEFT);
        } else {
            g.drawImage(p, x + w - 2, y + 2, Graphics.TOP | Graphics.RIGHT);
        }
        if (w >= 22 && h >= 30) {
            Image big = pip(suit(card), scale + 1);
            if (rank(card) >= 10) {
                g.setColor(red ? 0xF8D7D7 : 0xDDE3F0);
                g.fillRect(x + w / 4, y + h / 3, w / 2, h / 2);
                g.setColor(red ? 0xD32F2F : 0x1A1A1A);
                g.drawRect(x + w / 4, y + h / 3, w / 2, h / 2);
            }
            g.drawImage(big, x + w / 2, y + h * 7 / 12, Graphics.HCENTER | Graphics.VCENTER);
        }
    }

    public static void drawBack(Graphics g, int x, int y, int w, int h) {
        g.setColor(0xFDFDF8);
        g.fillRoundRect(x, y, w, h, 4, 4);
        g.setColor(0x1E4FA0);
        g.fillRect(x + 2, y + 2, w - 4, h - 4);
        g.setColor(0x3F7AD9);
        for (int k = 0; k < w + h; k += 4) {
            int x0 = x + 2 + Math.max(0, k - (h - 4)), y0 = y + 2 + Math.min(k, h - 4);
            int x1 = x + 2 + Math.min(k, w - 4), y1 = y + 2 + Math.max(0, k - (w - 4));
            g.drawLine(x0, y0, x1, y1);
        }
        g.setColor(0x6B6B6B);
        g.drawRoundRect(x, y, w - 1, h - 1, 4, 4);
    }

    /** Empty pile outline. */
    public static void drawSlot(Graphics g, int x, int y, int w, int h) {
        g.setColor(0x2E6B3F);
        g.drawRoundRect(x, y, w - 1, h - 1, 4, 4);
    }

    /** Green felt background. */
    public static void felt(Graphics g, int w, int h) {
        g.setColor(0x1F6B3A);
        g.fillRect(0, 0, w, h);
        g.setColor(0x237541);
        for (int y = 0; y < h; y += 4) for (int x = (y / 4 & 1) * 2; x < w; x += 4) g.fillRect(x, y, 1, 1);
    }
}
