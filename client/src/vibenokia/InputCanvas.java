package vibenokia;

import javax.microedition.lcdui.*;
import java.util.Vector;

/**
 * Text input directly on a Canvas: multitap, like on an ordinary phone.
 * Replaces Form/TextBox: they do not receive keyRepeated, and a held 0
 * (panic) while typing printed zeros instead of hiding the application.
 *
 * Layout: 2-9 letters (multitap, ~800 ms repeat window), 0 space,
 * 1 punctuation, # case/digits (abc -> ABC -> 123), * delete a character
 * (hold to clear the whole line), OK/Send submits, up/down history.
 */
public class InputCanvas extends Canvas implements CommandListener {

    public static final int MODE_LOWER = 0;
    public static final int MODE_UPPER = 1;
    public static final int MODE_NUM = 2;

    private static final int REPEAT_MS = 800;   // multitap window between presses

    // Character sets per key (index = digit); the digit itself is the last character
    private static final String[] TAP = {
        "",
        ".,?'!\"1-@():;/",
        "abc2", "def3", "ghi4", "jkl5",
        "mno6", "pqrs7", "tuv8", "wxyz9"
    };

    private final SnakeMIDlet midlet;
    private final Font font;
    private final Command okCmd;
    private final Command cancelCmd;

    private String title = "";
    private final StringBuffer buf = new StringBuffer();
    private int maxLen = 500;

    private int mode = MODE_LOWER;
    private int pendKey = -1;   // key with an unfinished multitap
    private int pendPos = -1;   // position of the pending character in the buffer
    private int pendIdx = 0;    // index in the TAP[pendKey] set
    private boolean pendShifted = false;   // pending letter typed in ABC - the shift is released after it
    private long lastPress = 0;

    // "Clear" (C) on Nokia is code -8: MIDP has no constant for it,
    // and Canvas has no KEY_CLEAR at all (that is what broke the build)
    private static final int KEY_CLR_NOKIA = -8;
    private boolean panicFired = false;   // held 0 has already fired (once per hold)

    // History of sent messages (lives in the midlet, shared); up/down scroll through it
    private final Vector history;
    private int histIdx = 0;
    private String stash = "";  // what was typed before entering the history

    public InputCanvas(SnakeMIDlet midlet, Vector history) {
        this.midlet = midlet;
        this.history = history;
        setFullScreenMode(true);
        font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        okCmd = new Command(Strings.send(), Command.OK, 1);
        cancelCmd = new Command(Strings.cancel(), Command.BACK, 1);
        addCommand(okCmd);
        addCommand(cancelCmd);
        setCommandListener(this);
    }

    /** Open input: title, initial text (draft), length limit. */
    /** Open input directly in digit mode - for codes and ids where letters are not needed. */
    public void openNumeric(String title, String initial, int maxLen) {
        open(title, initial, maxLen);
        mode = MODE_NUM;
        repaint();
    }

    public void open(String title, String initial, int maxLen) {
        this.title = title;
        this.maxLen = maxLen;
        buf.setLength(0);
        if (initial != null && initial.length() > 0) {
            buf.append(initial.length() > maxLen ? initial.substring(0, maxLen) : initial);
        }
        mode = MODE_LOWER;
        // on panic the display switches to the game and keyReleased(0) will not come back here -
        // reset the flag ourselves, otherwise the next hold of 0 would not trigger panic
        panicFired = false;
        resetPending();
        histIdx = history.size();
        stash = "";
        repaint();
    }

    public String getText() { return buf.toString(); }

    /** Hard reset without saving (panic): typed text must not leak out. */
    public void hardReset() {
        buf.setLength(0);
        resetPending();
        panicFired = false;
        repaint();
    }

    private void resetPending() { pendKey = -1; pendPos = -1; pendIdx = 0; pendShifted = false; }

    /** ABC shift is released AFTER the letter it raised (classic multitap). */
    private void commitShift() {
        if (pendShifted) {
            mode = MODE_LOWER;
            pendShifted = false;
        }
    }

    // --- INPUT ---

    private boolean appendChar(char c) {
        if (buf.length() >= maxLen) return false;
        buf.append(c);
        return true;
    }

    /** Case by mode: in ABC letters are typed in upper case. */
    private char applyCase(char c) {
        if (mode == MODE_UPPER && c >= 'a' && c <= 'z') return (char) (c - 32);
        return c;
    }

    private void pressDigit(int d) {
        long now = System.currentTimeMillis();
        String set = TAP[d];
        if (mode == MODE_NUM) {   // digit mode: a key immediately yields its digit
            resetPending();
            appendChar((char) ('0' + d));   // directly, not from TAP: for "1" the digit is not the last character
            repaint();
            return;
        }
        if (pendKey == d && now - lastPress <= REPEAT_MS) {
            // same key within the multitap window - replace the last character with the next one
            // (do NOT touch the case: "2,2" must give "AB", not "Aa")
            pendIdx = (pendIdx + 1) % set.length();
            buf.setCharAt(pendPos, applyCase(set.charAt(pendIdx)));
        } else {
            commitShift();   // the previous capital is closed - back to lower case
            resetPending();
            if (!appendChar(applyCase(set.charAt(0)))) return;   // length limit
            pendKey = d;
            pendIdx = 0;
            pendPos = buf.length() - 1;
            if (mode == MODE_UPPER) pendShifted = true;
        }
        lastPress = now;
        repaint();
    }

    private void pressZero() {
        commitShift();   // space closes the previous capital
        resetPending();
        appendChar(mode == MODE_NUM ? '0' : ' ');
        lastPress = System.currentTimeMillis();
        repaint();
    }

    private void backspace() {
        if (pendKey != -1) {          // delete a not yet confirmed character
            buf.deleteCharAt(pendPos);
            resetPending();
        } else if (buf.length() > 0) {
            buf.deleteCharAt(buf.length() - 1);
        }
        repaint();
    }

    private void setText(String s) {
        buf.setLength(0);
        if (s != null) buf.append(s.length() > maxLen ? s.substring(0, maxLen) : s);
        resetPending();
        repaint();
    }

    private void historyUp() {
        if (history.size() == 0 || histIdx == 0) return;
        if (histIdx == history.size()) stash = buf.toString();   // remember own text
        histIdx--;
        setText((String) history.elementAt(histIdx));
    }

    private void historyDown() {
        if (histIdx >= history.size()) return;
        histIdx++;
        setText(histIdx == history.size() ? stash : (String) history.elementAt(histIdx));
    }

    protected void keyPressed(int keyCode) {
        // Digits BEFORE getGameAction: on S30+ a digit may map to an arrow
        if (keyCode >= KEY_NUM0 && keyCode <= KEY_NUM9) {
            int d = keyCode - KEY_NUM0;
            if (d == 0) pressZero(); else pressDigit(d);
            return;
        }
        if (keyCode == KEY_POUND) {
            mode = (mode + 1) % 3;   // abc -> ABC -> 123
            resetPending();
            repaint();
            return;
        }
        if (keyCode == KEY_STAR || keyCode == KEY_CLR_NOKIA) { backspace(); return; }

        int ga = getGameAction(keyCode);
        if (ga == FIRE) { midlet.onInputDone(buf.toString()); return; }
        if (ga == UP) { historyUp(); return; }
        if (ga == DOWN) historyDown();
    }

    protected void keyRepeated(int keyCode) {
        // HELD 0 is panic: works while typing too (this is why
        // input moved from Form to Canvas). Once per hold.
        if (keyCode == KEY_NUM0) {
            if (!panicFired) { panicFired = true; midlet.onPanicHold(); }
            return;
        }
        if (keyCode == KEY_STAR) {   // held *: clear everything typed
            buf.setLength(0);
            resetPending();
            repaint();
        }
    }

    protected void keyReleased(int keyCode) {
        // releasing 0 clears the repeat block (if the event reaches here)
        if (keyCode == KEY_NUM0) panicFired = false;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == okCmd) midlet.onInputDone(buf.toString());
        else if (c == cancelCmd) midlet.onInputCancel();
    }

    // --- DRAWING ---

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(TerminalCanvas.COLOR_BG);
        g.fillRect(0, 0, w, h);
        g.setFont(font);

        int pad = 4;
        // header: title + mode indicator on the right
        String md = mode == MODE_NUM ? "123" : (mode == MODE_UPPER ? "ABC" : "abc");
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        String head = title;
        int maxHeadW = w - pad * 2 - font.stringWidth(md) - 10;
        if (maxHeadW < 8) maxHeadW = 8;
        while (head.length() > 3 && font.stringWidth(head) > maxHeadW)
            head = head.substring(0, head.length() - 2) + ".";
        g.drawString(head, pad, pad, Graphics.TOP | Graphics.LEFT);
        g.setColor(TerminalCanvas.COLOR_DIM);
        g.drawString(md, w - pad - font.stringWidth(md), pad, Graphics.TOP | Graphics.LEFT);
        int headerH = pad + font.getHeight() + pad;
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        g.drawLine(0, headerH - 1, w, headerH - 1);

        // text: word wrap, show the TAIL (like a real input field)
        int footerH = font.getHeight() + pad * 2;
        int areaTop = headerH + 2;
        int areaBottom = h - footerH - 2;
        int lineH = font.getHeight() + 2;
        int fit = (areaBottom - areaTop) / lineH;
        if (fit < 1) fit = 1;

        Vector rows = new Vector();
        String rest = buf.toString();
        int contentW = w - pad * 2;
        while (rest.length() > 0) {
            int end = 0, lastSp = -1, wd = 0;
            while (end < rest.length()) {
                wd += font.charWidth(rest.charAt(end));
                if (wd > contentW) break;
                if (rest.charAt(end) == ' ') lastSp = end;
                end++;
            }
            String chunk;
            if (end >= rest.length()) { chunk = rest; rest = ""; }
            else if (lastSp > 0) { chunk = rest.substring(0, lastSp); rest = rest.substring(lastSp + 1); }
            else if (end > 0) { chunk = rest.substring(0, end); rest = rest.substring(end); }
            else { chunk = rest.substring(0, 1); rest = rest.substring(1); }   // character wider than the line
            rows.addElement(chunk);
        }

        g.setColor(TerminalCanvas.COLOR_TEXT);
        int first = rows.size() - fit;
        if (first < 0) first = 0;
        int y = areaTop;
        for (int i = first; i < rows.size(); i++) {
            g.drawString((String) rows.elementAt(i), pad, y, Graphics.TOP | Graphics.LEFT);
            y += lineH;
        }

        // caret: a block at the end of the last line (or at the start if empty)
        int lastW = rows.size() > 0 ? font.stringWidth((String) rows.elementAt(rows.size() - 1)) : 0;
        int caretX = pad + lastW;
        int caretY = rows.size() > 0 ? y - lineH : areaTop;
        if (caretX + font.charWidth('M') > w - pad) { caretX = pad; caretY += lineH; }
        if (caretY > areaBottom - lineH) caretY = areaBottom - lineH;
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        g.fillRect(caretX, caretY + 1, font.charWidth('M') - 1, font.getHeight() - 2);

        // footer: hint and length counter
        int fy = h - font.getHeight() - pad;
        g.setColor(TerminalCanvas.COLOR_DIM);
        String hint = Strings.getLang() == 0 ? "* стереть" : "* clear";
        g.drawString(hint, pad, fy, Graphics.TOP | Graphics.LEFT);
        String cnt = buf.length() + "/" + maxLen;
        g.drawString(cnt, w - pad - font.stringWidth(cnt), fy, Graphics.TOP | Graphics.LEFT);
    }
}
