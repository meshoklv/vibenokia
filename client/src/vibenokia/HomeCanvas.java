package vibenokia;

import javax.microedition.lcdui.*;

/**
 * Main screen: a stack of tiles. Simple mode shows only Claude and Settings;
 * Telegram, OpenCode and Sessions appear when switched on in Settings > Features
 * (see Features). Header with the version, footer with server connectivity and an
 * update hint. Up/down select, OK or 5 opens, 1/2/3/4/6 open the tile with that
 * number (numbers follow the visible order), 9 help, 0 about, # check connectivity.
 */
public class HomeCanvas extends Canvas {

    public static final int TILE_TG = 0;
    public static final int TILE_CLAUDE = 1;
    public static final int TILE_OC = 2;
    public static final int TILE_SESSIONS = 3;
    public static final int TILE_SETTINGS = 4;
    public static final int TILE_COUNT = 5;
    /** Digit shortcut for the n-th visible tile (5 is "open selected"). */
    private static final int[] DIGITS = { 1, 2, 3, 4, 6 };

    private static final int PAD = 4;
    private static final int CARD_PAD = 3;
    private static final int GAP = 4;

    private VibeNokiaMIDlet midlet;
    private Font fT;    // tile title - bold mono
    private Font fS;    // everything else - regular mono
    private int fhT, fhS;
    private int sel = 0;                  // index into tiles[]
    private int[] tiles = { TILE_CLAUDE, TILE_SETTINGS };   // visible tile ids, in order

    // Live tile state - filled in by VibeNokiaMIDlet
    private int unread = 0;
    private String tgSub = "";
    private String claudeSub = "";
    private String ocSub = "";
    private int tgOnline = -1;     // -1 unknown, 0 offline, 1 online
    private int fullOnline = -1;
    private String updateHint = "";

    private boolean showSplash = true;

    // Overlay (help / about): when not null, drawn on top of the tiles
    private String overlayTitle = null;
    private String[] overlayLines = null;

    public HomeCanvas(VibeNokiaMIDlet midlet) {
        this.midlet = midlet;
        setFullScreenMode(true);
        fT = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);
        fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fhT = fT.getHeight();
        fhS = fS.getHeight();
    }

    public void hideSplash() { showSplash = false; repaint(); }

    /** Tile id of the current selection. */
    public int getSelected() { return tiles[sel]; }

    /** Rebuild the visible tile list from the enabled features (Settings is always last). */
    public void setTiles(int featureMask) {
        int cur = tiles[sel];
        int[] t = new int[TILE_COUNT];
        int n = 0;
        t[n++] = TILE_CLAUDE;
        if ((featureMask & Features.TELEGRAM) != 0) t[n++] = TILE_TG;
        if ((featureMask & Features.OPENCODE) != 0) t[n++] = TILE_OC;
        if ((featureMask & Features.SESSIONS) != 0) t[n++] = TILE_SESSIONS;
        t[n++] = TILE_SETTINGS;
        tiles = new int[n];
        for (int i = 0; i < n; i++) tiles[i] = t[i];
        sel = 0;
        for (int i = 0; i < n; i++) if (tiles[i] == cur) sel = i;
        repaint();
    }

    public boolean hasTile(int tile) {
        for (int i = 0; i < tiles.length; i++) if (tiles[i] == tile) return true;
        return false;
    }

    public void setTgState(int u, String sub) {
        unread = u;
        tgSub = sub == null ? "" : sub;
        repaint();
    }
    public void setClaudeSub(String s) { claudeSub = s == null ? "" : s; repaint(); }
    public void setOcSub(String s) { ocSub = s == null ? "" : s; repaint(); }
    public void setOnline(boolean tgRole, boolean ok) {
        if (tgRole) tgOnline = ok ? 1 : 0; else fullOnline = ok ? 1 : 0;
        repaint();
    }
    public void setUpdateHint(String s) { updateHint = s == null ? "" : s; repaint(); }

    public void showOverlay(String title, String[] lines) {
        overlayTitle = title;
        overlayLines = lines;
        repaint();
    }

    public boolean hasOverlay() { return overlayLines != null; }

    public void closeOverlay() {
        overlayLines = null;
        overlayTitle = null;
        repaint();
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(TerminalCanvas.COLOR_BG);
        g.fillRect(0, 0, w, h);

        if (showSplash) { paintSplash(g, w, h); return; }
        if (overlayLines != null) { paintOverlay(g, w, h); return; }

        // --- header ---
        g.setFont(fS);
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        g.drawString("VibeNokia", PAD, PAD, Graphics.TOP | Graphics.LEFT);
        String ver = "v" + VibeNokiaMIDlet.APP_VERSION;
        g.setColor(TerminalCanvas.COLOR_DIM);
        g.drawString(ver, w - PAD - fS.stringWidth(ver), PAD, Graphics.TOP | Graphics.LEFT);
        int headerH = PAD + fhS + PAD;
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        g.drawLine(0, headerH - 1, w, headerH - 1);

        // --- tiles ---
        int cardH = CARD_PAD * 2 + fhT + 2 + fhS;
        int y = headerH + 4;
        for (int i = 0; i < tiles.length; i++) {
            paintCard(g, i, tiles[i], PAD, y, w - PAD * 2, cardH);
            y += cardH + GAP;
        }

        // --- footer: connectivity + update ---
        int fy = h - fhS - PAD;
        g.setColor(TerminalCanvas.COLOR_BORDER);
        g.drawLine(0, fy - 3, w, fy - 3);
        int x = PAD;
        if (hasTile(TILE_TG)) {
            g.setColor(TerminalCanvas.COLOR_DIM);
            g.drawString("TG", x, fy, Graphics.TOP | Graphics.LEFT);
            x += fS.stringWidth("TG") + 2;
            x = paintDot(g, x, fy, tgOnline);
            x += 8;
        }
        String cl = Strings.claudeTile();
        g.setColor(TerminalCanvas.COLOR_DIM);
        g.drawString(cl, x, fy, Graphics.TOP | Graphics.LEFT);
        x += fS.stringWidth(cl) + 2;
        paintDot(g, x, fy, fullOnline);
        if (updateHint.length() > 0) {
            g.setColor(TerminalCanvas.COLOR_ACCENT);
            g.drawString(updateHint, w - PAD - fS.stringWidth(updateHint), fy,
                         Graphics.TOP | Graphics.LEFT);
        }
    }

    /** Connectivity dot: 1 - accent, 0 - red, -1 - grey question mark. Returns the new x. */
    private int paintDot(Graphics g, int x, int fy, int state) {
        if (state < 0) {
            g.setColor(TerminalCanvas.COLOR_DIM);
            g.drawString("?", x, fy, Graphics.TOP | Graphics.LEFT);
            return x + fS.charWidth('?');
        }
        g.setColor(state == 1 ? TerminalCanvas.COLOR_ACCENT : TerminalCanvas.COLOR_ERROR);
        int d = 6;
        g.fillArc(x, fy + fhS / 2 - d / 2, d, d, 0, 360);
        return x + d;
    }

    private void paintCard(Graphics g, int pos, int idx, int x, int y, int cw, int ch) {
        boolean isSel = (pos == sel);
        g.setColor(isSel ? TerminalCanvas.COLOR_ACCENT : TerminalCanvas.COLOR_BORDER);
        g.drawRect(x, y, cw - 1, ch - 1);
        if (isSel) g.drawRect(x + 1, y + 1, cw - 3, ch - 3);   // 2px selection frame

        String title = DIGITS[pos] + " " + tileTitle(idx);
        String sub = tileSub(idx);

        int tx = x + CARD_PAD + 2;
        int ty = y + CARD_PAD;
        int innerW = cw - (CARD_PAD + 2) * 2;

        // unread badge - only on the Telegram tile
        int badgeW = 0;
        if (idx == TILE_TG && unread > 0) {
            String uc = unread > 9 ? "9+" : String.valueOf(unread);
            badgeW = fS.stringWidth(uc) + 8;
            int bx = x + cw - CARD_PAD - 2 - badgeW;
            g.setColor(TerminalCanvas.COLOR_ACCENT);
            g.fillRoundRect(bx, ty, badgeW, fhS + 1, 6, 6);
            g.setColor(TerminalCanvas.COLOR_BG);
            g.drawString(uc, bx + 4, ty + 1, Graphics.TOP | Graphics.LEFT);
            badgeW += 4;
        }

        g.setFont(fT);
        g.setColor(isSel ? TerminalCanvas.COLOR_ACCENT : TerminalCanvas.COLOR_TEXT);
        g.drawString(fit(fT, title, innerW - badgeW), tx, ty, Graphics.TOP | Graphics.LEFT);

        g.setFont(fS);
        g.setColor(TerminalCanvas.COLOR_DIM);
        g.drawString(fit(fS, sub, innerW), tx, ty + fhT + 2, Graphics.TOP | Graphics.LEFT);
    }

    private String tileTitle(int idx) {
        switch (idx) {
            case TILE_TG: return Strings.telegram();
            case TILE_CLAUDE: return Strings.claudeTile();
            case TILE_OC: return "OpenCode";
            case TILE_SESSIONS: return Strings.sessions();
            default: return Strings.settingsFull();
        }
    }

    private String tileSub(int idx) {
        switch (idx) {
            case TILE_TG: return tgSub;
            case TILE_CLAUDE: return claudeSub.length() > 0 ? claudeSub : Strings.chatSub();
            case TILE_OC: return ocSub;
            case TILE_SESSIONS: return Strings.sessionsSub();
            default: return Strings.settingsSub();
        }
    }

    /** Truncate a string to fit the width, with ".." as the tail. */
    private String fit(Font f, String s, int maxW) {
        if (f.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && f.stringWidth(s) + f.stringWidth("..") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private void paintOverlay(Graphics g, int w, int h) {
        g.setFont(fS);
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        g.drawString(overlayTitle, PAD, PAD, Graphics.TOP | Graphics.LEFT);
        g.drawLine(0, PAD + fhS + 3, w, PAD + fhS + 3);
        int y = PAD + fhS + 8;
        g.setColor(TerminalCanvas.COLOR_DIM);
        for (int i = 0; i < overlayLines.length && y < h - fhS - PAD; i++) {
            g.drawString(fit(fS, overlayLines[i], w - PAD * 2), PAD, y, Graphics.TOP | Graphics.LEFT);
            y += fhS + 2;
        }
        String hint = Strings.anyKeyBack();
        g.setColor(TerminalCanvas.COLOR_BORDER);
        g.drawString(hint, PAD, h - fhS - PAD, Graphics.TOP | Graphics.LEFT);
    }

    // The splash mascot now lives here (moved from TerminalCanvas as is)
    private void paintSplash(Graphics g, int w, int h) {
        int[][] mascot = {
            {0,0,0,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0,0,0},
            {0,0,0,0,0,1,1,1,1,1,1,1,1,1,1,0,0,0,0,0},
            {0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0},
            {0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0},
            {0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0},
            {0,0,0,1,1,1,2,2,1,1,1,1,2,2,1,1,1,0,0,0},
            {0,0,0,1,1,1,2,2,1,1,1,1,2,2,1,1,1,0,0,0},
            {0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0},
            {0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0},
            {0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0},
            {0,0,0,0,0,1,1,1,1,1,1,1,1,1,1,0,0,0,0,0},
            {0,0,0,0,0,1,1,0,1,1,1,1,0,1,1,0,0,0,0,0},
            {0,0,0,0,0,1,1,0,1,1,1,1,0,1,1,0,0,0,0,0},
            {0,0,0,0,0,3,3,0,3,3,3,3,0,3,3,0,0,0,0,0},
        };
        int px = 5;
        int mw = mascot[0].length * px, mh = mascot.length * px;
        int ox = (w - mw) / 2, oy = h / 2 - mh / 2 - 20;
        for (int r = 0; r < mascot.length; r++)
            for (int c = 0; c < mascot[r].length; c++) {
                int v = mascot[r][c];
                if (v == 0) continue;
                if (v == 1) g.setColor(0xD4835C);
                else if (v == 2) g.setColor(0x2A2A2A);
                else g.setColor(0x9B5F3C);
                g.fillRect(ox + c * px, oy + r * px, px, px);
            }
        Font tf = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);
        g.setFont(tf);
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        String t = "VibeNokia";
        g.drawString(t, (w - tf.stringWidth(t)) / 2, oy + mh + 12, Graphics.TOP | Graphics.LEFT);
        g.setFont(fS);
        g.setColor(TerminalCanvas.COLOR_DIM);
        String s = "v" + VibeNokiaMIDlet.APP_VERSION;
        g.drawString(s, (w - fS.stringWidth(s)) / 2,
                     oy + mh + 12 + tf.getHeight() + 4, Graphics.TOP | Graphics.LEFT);
    }

    protected void keyPressed(int keyCode) {
        if (showSplash) return;
        if (overlayLines != null) {          // any key closes the overlay
            overlayLines = null;
            overlayTitle = null;
            repaint();
            return;
        }
        // IMPORTANT: check digits BEFORE getGameAction - on S30+ the digit 2 may
        // map to UP, but here 2 = "open Claude"
        switch (keyCode) {
            case KEY_NUM1: openByDigit(0); return;
            case KEY_NUM2: openByDigit(1); return;
            case KEY_NUM3: openByDigit(2); return;
            case KEY_NUM4: openByDigit(3); return;
            case KEY_NUM6: openByDigit(4); return;
            case KEY_NUM5: midlet.onHomeOpen(tiles[sel]); return;
            case KEY_NUM9: midlet.onHomeHelp(); return;
            case KEY_NUM0: midlet.onHomeAbout(); return;
            case KEY_POUND: midlet.onHomePingNow(); return;
        }
        int ga = getGameAction(keyCode);
        if (ga == UP)   { sel = (sel + tiles.length - 1) % tiles.length; repaint(); return; }
        if (ga == DOWN) { sel = (sel + 1) % tiles.length; repaint(); return; }
        if (ga == FIRE) { midlet.onHomeOpen(tiles[sel]); return; }
    }

    private void openByDigit(int pos) {
        if (pos < tiles.length) midlet.onHomeOpen(tiles[pos]);
    }

    protected void keyRepeated(int keyCode) {
        if (showSplash || overlayLines != null) return;
        int ga = getGameAction(keyCode);
        if (ga == UP || ga == DOWN) keyPressed(keyCode);
    }

}
