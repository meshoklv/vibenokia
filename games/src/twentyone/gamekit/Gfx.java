package gamekit;

import java.util.Vector;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Drawing helpers shared by all games. Integer-only, allocation-light. */
public final class Gfx {
    public static final Font SMALL = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    public static final Font SMALL_B = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
    public static final Font MEDIUM = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    public static final Font LARGE = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);

    public static final int TL = Graphics.TOP | Graphics.LEFT;
    public static final int TC = Graphics.TOP | Graphics.HCENTER;
    public static final int TR = Graphics.TOP | Graphics.RIGHT;
    public static final int CC = Graphics.VCENTER | Graphics.HCENTER;

    private Gfx() {}

    /** Largest of LARGE/MEDIUM/SMALL that fits text in maxW. */
    public static Font fit(String s, int maxW) {
        if (LARGE.stringWidth(s) <= maxW) return LARGE;
        if (MEDIUM.stringWidth(s) <= maxW) return MEDIUM;
        return SMALL_B;
    }

    /** Lighten (pct > 0) or darken (pct < 0) a colour. */
    public static int shade(int c, int pct) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        if (pct >= 0) {
            r += (255 - r) * pct / 100;
            g += (255 - g) * pct / 100;
            b += (255 - b) * pct / 100;
        } else {
            r += r * pct / 100;
            g += g * pct / 100;
            b += b * pct / 100;
        }
        return (r << 16) | (g << 8) | b;
    }

    /** Mix two colours, t in 0..256. */
    public static int mix(int a, int b, int t) {
        int r = ((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t >> 8);
        int g = ((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t >> 8);
        int bl = (a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * t >> 8);
        return (r << 16) | (g << 8) | bl;
    }

    public static void text(Graphics g, String s, int x, int y, int anchor, Font f, int color) {
        g.setFont(f);
        g.setColor(color);
        g.drawString(s, x, y, anchor);
    }

    public static void shadowText(Graphics g, String s, int x, int y, int anchor, Font f, int color, int shadow) {
        g.setFont(f);
        g.setColor(shadow);
        g.drawString(s, x + 1, y + 1, anchor);
        g.setColor(color);
        g.drawString(s, x, y, anchor);
    }

    /** Filled box with a 1px border and a lighter inner top edge. */
    public static void panel(Graphics g, int x, int y, int w, int h, int fill, int border) {
        g.setColor(fill);
        g.fillRect(x, y, w, h);
        g.setColor(border);
        g.drawRect(x, y, w - 1, h - 1);
        g.setColor(shade(fill, 25));
        g.drawLine(x + 1, y + 1, x + w - 2, y + 1);
    }

    /** Raised 3D block (classic handset look). */
    public static void bevel(Graphics g, int x, int y, int w, int h, int base) {
        g.setColor(base);
        g.fillRect(x, y, w, h);
        g.setColor(shade(base, 45));
        g.drawLine(x, y, x + w - 1, y);
        g.drawLine(x, y, x, y + h - 1);
        g.setColor(shade(base, -45));
        g.drawLine(x + w - 1, y + 1, x + w - 1, y + h - 1);
        g.drawLine(x + 1, y + h - 1, x + w - 1, y + h - 1);
    }

    /** Darken an area with a scanline pattern (no alpha needed). */
    public static void dim(Graphics g, int x, int y, int w, int h) {
        g.setColor(0x000000);
        for (int yy = y; yy < y + h; yy += 2) g.drawLine(x, yy, x + w - 1, yy);
    }

    /** Horizontal progress bar. */
    public static void bar(Graphics g, int x, int y, int w, int h, int v, int max, int fg, int bg) {
        g.setColor(bg);
        g.fillRect(x, y, w, h);
        if (max > 0 && v > 0) {
            g.setColor(fg);
            g.fillRect(x, y, Math.min(w, w * v / max), h);
        }
        g.setColor(shade(bg, 40));
        g.drawRect(x, y, w - 1, h - 1);
    }

    /** Title screen background: dark gradient bands plus a dot grid. */
    public static void backdrop(Graphics g, int w, int h, int accent) {
        int top = shade(accent, -82), bottom = 0x05080C;
        int bands = 16;
        for (int i = 0; i < bands; i++) {
            g.setColor(mix(top, bottom, i * 256 / bands));
            int y0 = i * h / bands, y1 = (i + 1) * h / bands;
            g.fillRect(0, y0, w, y1 - y0);
        }
        g.setColor(shade(accent, -65));
        for (int y = 6; y < h; y += 8) for (int x = 4; x < w; x += 8) g.fillRect(x, y, 1, 1);
    }

    /** Default title art: a bouncing row of bevelled pixel blocks. */
    public static void titleBlocks(Graphics g, int x, int y, int w, int h, int accent, int clock) {
        int s = Math.max(6, Math.min(14, h / 4));
        int n = Math.min(7, w / (s + 2));
        int x0 = x + (w - n * (s + 2)) / 2;
        int cy = y + h / 2 - s / 2;
        for (int i = 0; i < n; i++) {
            int dy = FMath.sin((clock * 8 + i * 32) & 255) * (h / 6) >> 10;
            int c = mix(accent, 0xFFFFFF, i * 30);
            bevel(g, x0 + i * (s + 2), cy + dy, s, s, c);
        }
    }

    /** Word-wrap text to lines no wider than maxW. */
    public static Vector wrap(String text, Font f, int maxW) {
        Vector out = new Vector();
        int start = 0, len = text.length();
        while (start < len) {
            int end = start, lastSpace = -1;
            while (end < len && text.charAt(end) != '\n') {
                if (text.charAt(end) == ' ') lastSpace = end;
                if (f.substringWidth(text, start, end - start + 1) > maxW) break;
                end++;
            }
            if (end < len && text.charAt(end) != '\n' && lastSpace > start) end = lastSpace;
            if (end == start) end = start + 1;
            out.addElement(text.substring(start, end));
            start = end;
            while (start < len && (text.charAt(start) == ' ' || text.charAt(start) == '\n')) {
                if (text.charAt(start) == '\n') { start++; break; }
                start++;
            }
        }
        return out;
    }

    /**
     * Build a sprite from rows of palette indices. '.' or ' ' is transparent,
     * '0'-'9' and 'a'-'f' index into pal (0xRRGGBB). scale >= 1.
     */
    public static Image sprite(String[] rows, int[] pal, int scale) {
        int h = rows.length, w = rows[0].length();
        int[] px = new int[w * h * scale * scale];
        int sw = w * scale;
        for (int y = 0; y < h; y++) {
            String r = rows[y];
            for (int x = 0; x < w; x++) {
                char c = x < r.length() ? r.charAt(x) : '.';
                int v;
                if (c >= '0' && c <= '9') v = 0xFF000000 | pal[c - '0'];
                else if (c >= 'a' && c <= 'f') v = 0xFF000000 | pal[c - 'a' + 10];
                else v = 0;
                for (int j = 0; j < scale; j++) {
                    int o = (y * scale + j) * sw + x * scale;
                    for (int i = 0; i < scale; i++) px[o + i] = v;
                }
            }
        }
        return Image.createRGBImage(px, sw, h * scale, true);
    }

    /** mm:ss from ticks at the given tick length. */
    public static String time(int ticks, int tickMs) {
        int s = ticks * tickMs / 1000;
        int m = s / 60;
        s %= 60;
        return m + (s < 10 ? ":0" : ":") + s;
    }

    /** Small key hint text at the bottom of the screen. */
    public static void hint(Graphics g, String s, int w, int h) {
        g.setFont(SMALL);
        g.setColor(0x000000);
        g.drawString(s, w / 2 + 1, h - SMALL.getHeight(), TC);
        g.setColor(0xC8D4DC);
        g.drawString(s, w / 2, h - SMALL.getHeight() - 1, TC);
    }

    /** Pick a cell size so a cols x rows grid fits in w x h. */
    public static int cell(int w, int h, int cols, int rows) {
        return Math.max(1, Math.min(w / cols, h / rows));
    }

    /** Draw a filled circle centred on (cx, cy). */
    public static void disc(Graphics g, int cx, int cy, int r) {
        g.fillArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
    }

    /** Draw a circle outline centred on (cx, cy). */
    public static void ring(Graphics g, int cx, int cy, int r) {
        g.drawArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
    }
}
