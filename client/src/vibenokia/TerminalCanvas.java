package vibenokia;

import javax.microedition.lcdui.*;
import java.util.Vector;
import java.util.Timer;
import java.util.TimerTask;

public class TerminalCanvas extends Canvas {

    public static final int COLOR_BG = 0x000000;
    public static final int COLOR_TEXT = 0xE8E0D6;
    public static final int COLOR_ACCENT = 0xE8846B;
    public static final int COLOR_DIM = 0x888888;
    public static final int COLOR_INPUT = 0xF5C2B0;
    public static final int COLOR_RESPONSE = 0xE8E0D6;
    public static final int COLOR_ERROR = 0xFF6B6B;
    public static final int COLOR_BORDER = 0x444444;
    public static final int COLOR_RECORDING = 0xFF4444;
    public static final int COLOR_INPUT_BG = 0x1A1A1A;

    public static final int VOICE_NONE = 0;
    public static final int VOICE_RECORDING = 1;
    public static final int VOICE_READY = 2;

    private static final int PAD = 4;
    private static final int LINE_SPACING = 2;

    private Vector lines;
    private Vector lineColors;
    private int scrollOffset = 0;
    private Font font;
    private int lineHeight;
    private int visibleLines;
    private int headerH;
    private int promptH;
    private int contentW;
    private String modelName = "Opus 5";
    private String title = "Claude Code";

    private int voiceState = VOICE_NONE;
    private int recordSeconds = 0;
    private boolean starHeld = false;

    // Spinner (animated, shown in prompt area only)
    private boolean spinnerActive = false;
    private int spinnerIdx = 0;
    private int spinnerTicks = 0;
    private String spinnerStatus = "";
    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    private Timer spinnerTimer;

    // Connectivity
    private boolean serverOnline = false;

    // Splash
    private boolean showSplash = false;   // splash moved to HomeCanvas
    private int unreadCount = 0;   // unread Telegram messages - number next to the connectivity dot

    public void setUnread(int n) {
        if (n != unreadCount) { unreadCount = n; repaint(); }
    }

    private VibeNokiaMIDlet midlet;

    public TerminalCanvas(VibeNokiaMIDlet midlet) {
        this.midlet = midlet;
        setFullScreenMode(true);
        lines = new Vector();
        lineColors = new Vector();
        font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        lineHeight = font.getHeight() + LINE_SPACING;
        headerH = font.getHeight() + PAD * 2 + 1;
        promptH = font.getHeight() + PAD * 2 + 1;
        contentW = getWidth() - PAD * 2 - 4;
        visibleLines = (getHeight() - headerH - promptH) / lineHeight;
        // sizes can be zero before the Canvas is shown - guard against negative
        // width (it sends word wrapping into an infinite loop)
        if (contentW < 16) contentW = 16;
        if (visibleLines < 1) visibleLines = 1;
    }

    /** Font size: 0 = small, 1 = medium. Recomputes the whole layout. */
    public void setFontSize(int size) {
        font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN,
                            size == 1 ? Font.SIZE_MEDIUM : Font.SIZE_SMALL);
        lineHeight = font.getHeight() + LINE_SPACING;
        headerH = font.getHeight() + PAD * 2 + 1;
        promptH = font.getHeight() + PAD * 2 + 1;
        sizeChanged(getWidth(), getHeight());
    }

    /** S30+ changes sizes after setFullScreenMode - recompute the layout. */
    protected void sizeChanged(int w, int h) {
        contentW = w - PAD * 2 - 4;
        if (contentW < 16) contentW = 16;
        visibleLines = (h - headerH - promptH) / lineHeight;
        if (visibleLines < 1) visibleLines = 1;
        repaint();
    }

    public Font getFont() { return font; }
    public int getContentWidth() { return contentW; }

    /** Kept as a stub: the splash moved to HomeCanvas. */
    public void hideSplash() { showSplash = false; }

    public void setModelName(String name) {
        modelName = name;
        repaint();
    }

    /** Title on the left of the header: "Claude Code" or "TG: <name>". */
    public void setTitle(String t) {
        title = t;
        repaint();
    }

    public void setVoiceState(int state, int seconds) {
        voiceState = state;
        recordSeconds = seconds;
        repaint();
    }

    public int getVoiceState() { return voiceState; }

    public void setServerOnline(boolean online) {
        serverOnline = online;
        repaint();
    }

    // Spinner control (animated in prompt area, chat text stays still)
    public void startSpinner(String status) {
        spinnerActive = true;
        spinnerStatus = status;
        spinnerIdx = 0;
        spinnerTicks = 0;
        if (spinnerTimer != null) spinnerTimer.cancel();
        spinnerTimer = new Timer();
        spinnerTimer.schedule(new TimerTask() {
            public void run() {
                if (spinnerActive) {
                    spinnerIdx = (spinnerIdx + 1) % 4;
                    spinnerTicks++;
                    repaint();
                }
            }
        }, 200, 200);
        repaint();
    }

    public void updateSpinner(String status) {
        spinnerStatus = status;
        spinnerTicks = 0;
        repaint();
    }

    public void stopSpinner() {
        spinnerActive = false;
        if (spinnerTimer != null) { spinnerTimer.cancel(); spinnerTimer = null; }
        repaint();
    }

    public void addLine(String text, int color) {
        // Do not yank the user out of scrolling: auto-scroll only if already at the bottom
        boolean atBottom = scrollOffset >= lines.size() - visibleLines - 1;
        lines.addElement(text);
        lineColors.addElement(new Integer(color));
        // Buffer cap - on phone memory an unbounded history ends in OOM
        while (lines.size() > 400) {
            lines.removeElementAt(0);
            lineColors.removeElementAt(0);
            if (scrollOffset > 0) scrollOffset--;
        }
        if (atBottom && lines.size() > visibleLines) {
            scrollOffset = lines.size() - visibleLines;
        }
        repaint();
    }

    public void removeLastLine() {
        if (lines.size() > 0) {
            lines.removeElementAt(lines.size() - 1);
            lineColors.removeElementAt(lineColors.size() - 1);
        }
    }

    public void clear() {
        lines.removeAllElements();
        lineColors.removeAllElements();
        scrollOffset = 0;
        repaint();
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        // Black background always
        g.setColor(COLOR_BG);
        g.fillRect(0, 0, w, h);

        g.setFont(font);

        // --- HEADER ---
        int ty = PAD;
        g.setColor(COLOR_ACCENT);
        // the title must not overlap the caption on the right
        // a long caption on the right shrinks itself instead of overlapping the title
        String mn = modelName;
        int halfW = w / 2 - PAD - 8;
        while (mn.length() > 3 && font.stringWidth(mn) > halfW) {
            mn = mn.substring(0, mn.length() - 2) + ".";
        }
        String head = title;
        int maxHeadW = w - PAD * 2 - font.stringWidth(mn) - 12;
        if (maxHeadW < 8) maxHeadW = 8;
        while (head.length() > 3 && font.stringWidth(head) > maxHeadW) {
            head = head.substring(0, head.length() - 2) + ".";
        }
        g.drawString(head, PAD, ty, Graphics.TOP | Graphics.LEFT);
        if (mn.length() > 0) {
            g.setColor(COLOR_DIM);
            g.drawString(mn, w - PAD - font.stringWidth(mn), ty, Graphics.TOP | Graphics.LEFT);
        }
        // Connectivity dot in the middle of the header: connected - accent, not - red
        {
            g.setColor(serverOnline ? COLOR_ACCENT : COLOR_ERROR);
            int dotSize = 6;
            int dotX = w / 2 - dotSize / 2;
            int dotY = ty + font.getHeight() / 2 - dotSize / 2;
            g.fillArc(dotX, dotY, dotSize, dotSize, 0, 360);
            if (unreadCount > 0) {
                // unread Telegram count right after the dot
                g.setColor(COLOR_ACCENT);
                String uc = unreadCount > 9 ? "9+" : String.valueOf(unreadCount);
                g.drawString(uc, dotX + dotSize + 3, ty, Graphics.TOP | Graphics.LEFT);
            }
        }
        int lineY = headerH - 1;
        g.setColor(COLOR_ACCENT);
        g.drawLine(0, lineY, w, lineY);

        // --- CHAT CONTENT ---
        int startIdx = scrollOffset;
        int endIdx = Math.min(lines.size(), startIdx + visibleLines);
        int cy = headerH + 1;

        for (int i = startIdx; i < endIdx; i++) {
            String line = (String) lines.elementAt(i);
            int color = ((Integer) lineColors.elementAt(i)).intValue();
            if (color == COLOR_INPUT) {
                g.setColor(COLOR_INPUT_BG);
                g.fillRect(0, cy, w - 4, lineHeight);
            }
            g.setColor(color);
            g.drawString(line, PAD + 2, cy, Graphics.TOP | Graphics.LEFT);
            cy += lineHeight;
        }

        // Scrollbar
        if (lines.size() > visibleLines) {
            int trackTop = headerH + 2;
            int trackH = h - headerH - promptH - 4;
            int totalL = lines.size();
            g.setColor(COLOR_BORDER);
            g.drawLine(w - 2, trackTop, w - 2, trackTop + trackH);
            int thumbH = Math.max(8, trackH * visibleLines / totalL);
            int thumbY = trackTop + (trackH - thumbH) * scrollOffset / Math.max(1, totalL - visibleLines);
            g.setColor(COLOR_ACCENT);
            g.fillRect(w - 3, thumbY, 2, thumbH);
        }

        // --- PROMPT AREA ---
        int promptY = h - promptH;
        g.setColor(COLOR_BORDER);
        g.drawLine(0, promptY, w, promptY);
        int ptY = promptY + PAD + 1;

        if (voiceState == VOICE_RECORDING) {
            g.setColor(COLOR_RECORDING);
            g.fillRect(PAD + 1, ptY + 2, 6, 6);
            g.drawString(" REC " + recordSeconds + "s", PAD + 8, ptY, Graphics.TOP | Graphics.LEFT);
        } else if (voiceState == VOICE_READY) {
            g.setColor(COLOR_ACCENT);
            g.drawString("rec " + recordSeconds + "s  OK=send", PAD, ptY, Graphics.TOP | Graphics.LEFT);
        } else if (spinnerActive) {
            g.setColor(COLOR_ACCENT);
            int secs = spinnerTicks / 5;
            String spinTxt = SPINNER[spinnerIdx] + " " + spinnerStatus;
            if (secs > 0) spinTxt += " (" + secs + "s)";
            g.drawString(spinTxt, PAD, ptY, Graphics.TOP | Graphics.LEFT);
            // Hint on the right: how to cancel while it is "thinking"
            String hint = "#=" + Strings.stop();
            g.setColor(COLOR_DIM);
            g.drawString(hint, w - PAD - font.stringWidth(hint), ptY, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(COLOR_ACCENT);
            g.drawString(">", PAD, ptY, Graphics.TOP | Graphics.LEFT);
            g.setColor(COLOR_DIM);
            g.drawString("_", PAD + font.charWidth('>') + 3, ptY, Graphics.TOP | Graphics.LEFT);
        }
    }


    protected void keyPressed(int keyCode) {
        if (showSplash) return;

        if (keyCode == KEY_NUM2 || getGameAction(keyCode) == UP) {
            if (scrollOffset > 0) { scrollOffset--; repaint(); }
            return;
        }
        if (keyCode == KEY_NUM8 || getGameAction(keyCode) == DOWN) {
            if (scrollOffset < lines.size() - visibleLines) { scrollOffset++; repaint(); }
            return;
        }
        if (keyCode == KEY_POUND) {
            if (midlet.isRequestActive()) {
                midlet.onStopRequest();
                return;
            }
            scrollOffset = Math.min(Math.max(0, lines.size() - visibleLines), scrollOffset + visibleLines);
            repaint();
            return;
        }
        if (keyCode == KEY_NUM0) {
            scrollOffset = Math.max(0, scrollOffset - visibleLines);
            repaint();
            return;
        }
        if (keyCode == KEY_NUM1) {
            // home, to the main screen
            midlet.onGoHome();
            return;
        }
        if (keyCode == KEY_NUM3) {
            // quick model choice from a list
            midlet.onPickModel();
            return;
        }
        if (keyCode == KEY_NUM4) {   // to the very beginning of the chat
            scrollOffset = 0;
            repaint();
            return;
        }
        if (keyCode == KEY_NUM5) {   // refresh the Telegram conversation
            midlet.onRefreshChat();
            return;
        }
        if (keyCode == KEY_NUM7) {   // clear the screen
            midlet.onClearScreen();
            return;
        }
        if (keyCode == KEY_NUM9) {   // key help
            midlet.onShowHelp();
            return;
        }
        if (keyCode == KEY_NUM6) {   // to the very end of the chat
            scrollOffset = Math.max(0, lines.size() - visibleLines);
            repaint();
            return;
        }

        if (keyCode == KEY_STAR) {
            if (voiceState == VOICE_RECORDING) {
                starHeld = false;
                midlet.onVoiceStop();
            } else if (voiceState == VOICE_NONE) {
                starHeld = true;
            }
            return;
        }

        if (getGameAction(keyCode) == FIRE) {
            if (voiceState == VOICE_READY) {
                midlet.onVoiceSend();
            } else if (voiceState == VOICE_RECORDING) {
                midlet.onVoiceStop();
            } else if (voiceState == VOICE_NONE) {
                midlet.onOkPressed();
            }
            return;
        }
    }

    protected void keyRepeated(int keyCode) {
        if (showSplash) return;


        if (keyCode == KEY_NUM2 || getGameAction(keyCode) == UP) {
            if (scrollOffset > 0) { scrollOffset--; repaint(); }
            return;
        }
        if (keyCode == KEY_NUM8 || getGameAction(keyCode) == DOWN) {
            if (scrollOffset < lines.size() - visibleLines) { scrollOffset++; repaint(); }
            return;
        }
        if (keyCode == KEY_STAR && starHeld && voiceState == VOICE_NONE) {
            starHeld = false;
            midlet.onVoiceStart();
        }
    }


    /** Reset transient input states (cancel): star, voice, spinner. */
    public void resetTransient() {
        starHeld = false;
        voiceState = VOICE_NONE;
        recordSeconds = 0;
        stopSpinner();
    }
}
