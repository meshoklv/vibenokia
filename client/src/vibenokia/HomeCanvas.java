package vibenokia;

import javax.microedition.lcdui.*;

/**
 * Main screen: a stack of tiles (Telegram/Claude/OpenCode/Sessions/Settings),
 * a header with the version, a footer with server connectivity and an update hint.
 * Up/down select, OK or 5 opens, 1/2/3/4/7 open directly,
 * 9 help, 0 about, # check connectivity, * game.
 */
public class HomeCanvas extends Canvas {

    public static final int TILE_TG = 0;
    public static final int TILE_CLAUDE = 1;
    public static final int TILE_OC = 2;
    public static final int TILE_SESSIONS = 3;
    public static final int TILE_SETTINGS = 4;
    public static final int TILE_COUNT = 5;

    private static final int PAD = 4;
    private static final int CARD_PAD = 3;
    private static final int GAP = 4;

    private SnakeMIDlet midlet;
    private Font fT;    // tile title - bold mono
    private Font fS;    // everything else - regular mono
    private int fhT, fhS;
    private int sel = 0;
    private boolean panicFired = false;   // held 0 has already fired (once per hold)

    // Live tile state - filled in by SnakeMIDlet
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

    public HomeCanvas(SnakeMIDlet midlet) {
        this.midlet = midlet;
        setFullScreenMode(true);
        fT = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);
        fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fhT = fT.getHeight();
        fhS = fS.getHeight();
    }

    public void hideSplash() { showSplash = false; repaint(); }

    public int getSelected() { return sel; }

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

    /** Reset the 0 repeat block: common cleanup on panic/hide (enterMask). */
    public void resetPanic() { panicFired = false; }

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
        String ver = "v" + SnakeMIDlet.APP_VERSION;
        g.setColor(TerminalCanvas.COLOR_DIM);
        g.drawString(ver, w - PAD - fS.stringWidth(ver), PAD, Graphics.TOP | Graphics.LEFT);
        int headerH = PAD + fhS + PAD;
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        g.drawLine(0, headerH - 1, w, headerH - 1);

        // --- tiles ---
        int cardH = CARD_PAD * 2 + fhT + 2 + fhS;
        int y = headerH + 4;
        for (int i = 0; i < TILE_COUNT; i++) {
            paintCard(g, i, PAD, y, w - PAD * 2, cardH);
            y += cardH + GAP;
        }

        // --- footer: connectivity + update ---
        int fy = h - fhS - PAD;
        g.setColor(TerminalCanvas.COLOR_BORDER);
        g.drawLine(0, fy - 3, w, fy - 3);
        int x = PAD;
        g.setColor(TerminalCanvas.COLOR_DIM);
        g.drawString("TG", x, fy, Graphics.TOP | Graphics.LEFT);
        x += fS.stringWidth("TG") + 2;
        x = paintDot(g, x, fy, tgOnline);
        x += 8;
        g.setColor(TerminalCanvas.COLOR_DIM);
        g.drawString("Клод", x, fy, Graphics.TOP | Graphics.LEFT);
        x += fS.stringWidth("Клод") + 2;
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

    private void paintCard(Graphics g, int idx, int x, int y, int cw, int ch) {
        boolean isSel = (idx == sel);
        g.setColor(isSel ? TerminalCanvas.COLOR_ACCENT : TerminalCanvas.COLOR_BORDER);
        g.drawRect(x, y, cw - 1, ch - 1);
        if (isSel) g.drawRect(x + 1, y + 1, cw - 3, ch - 3);   // 2px selection frame

        String digit = idx == TILE_TG ? "1" : idx == TILE_CLAUDE ? "2"
                     : idx == TILE_OC ? "3" : idx == TILE_SESSIONS ? "4" : "7";
        String title = digit + " " + tileTitle(idx);
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
            case TILE_CLAUDE: return claudeSub;
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
        String s = "v" + SnakeMIDlet.APP_VERSION;
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
            case KEY_NUM1: midlet.onHomeOpen(TILE_TG); return;
            case KEY_NUM2: midlet.onHomeOpen(TILE_CLAUDE); return;
            case KEY_NUM3: midlet.onHomeOpen(TILE_OC); return;
            case KEY_NUM4: midlet.onHomeOpen(TILE_SESSIONS); return;
            case KEY_NUM7: midlet.onHomeOpen(TILE_SETTINGS); return;
            case KEY_NUM5: midlet.onHomeOpen(sel); return;
            case KEY_NUM9: midlet.onHomeHelp(); return;
            case KEY_NUM0: panicFired = false; midlet.onHomeAbout(); return;
            case KEY_POUND: midlet.onHomePingNow(); return;
            case KEY_STAR: midlet.onHomeSnake(); return;   // hide in the game
        }
        int ga = getGameAction(keyCode);
        if (ga == UP)   { sel = (sel + TILE_COUNT - 1) % TILE_COUNT; repaint(); return; }
        if (ga == DOWN) { sel = (sel + 1) % TILE_COUNT; repaint(); return; }
        if (ga == FIRE) { midlet.onHomeOpen(sel); return; }
    }

    protected void keyRepeated(int keyCode) {
        // HELD 0 is panic: checked BEFORE splash/overlay, works everywhere.
        // A short 0 ("About") stays in keyPressed as before.
        if (keyCode == KEY_NUM0) {
            if (!panicFired) { panicFired = true; midlet.onPanicHold(); }
            return;
        }
        if (showSplash || overlayLines != null) return;
        int ga = getGameAction(keyCode);
        if (ga == UP || ga == DOWN) keyPressed(keyCode);
    }

    protected void keyReleased(int keyCode) {
        // releasing 0 clears the repeat block (if the event reaches here)
        if (keyCode == KEY_NUM0) panicFired = false;
    }
}
