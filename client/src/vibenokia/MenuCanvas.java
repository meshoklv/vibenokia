package vibenokia;

import javax.microedition.lcdui.*;
import java.util.Vector;

/**
 * List on a Canvas instead of List/Form.
 *
 * Why: MIDP delivers keyRepeated ONLY to Canvas screens. While the menu, contacts,
 * sessions and settings were List/Form, a held 0 (panic) never reached the
 * application there, so the most sensitive screen (contact names)
 * was not hidden. This behaves like List.IMPLICIT but is a Canvas:
 * arrows/2/8 select, OK/5 opens, soft keys are the same Commands that
 * used to be attached to the List, and holding 0 jumps to the game from anywhere.
 */
public class MenuCanvas extends Canvas {

    private static final int PAD = 6;
    private static final int ROW_PAD = 3;

    private final SnakeMIDlet midlet;
    private final String header;
    private final Vector items = new Vector();

    private int sel = 0;
    private int top = 0;                 // first visible row
    private int rowsVisible = 1;

    private Command selectCmd;           // what we send to the listener on OK/FIRE
    private CommandListener listener;
    private boolean panicFired = false;  // held 0 has already fired (once per hold)

    private final Font fT, fS;
    private final int fhT, fhS;

    public MenuCanvas(SnakeMIDlet midlet, String header) {
        this.midlet = midlet;
        this.header = header != null ? header : "";
        setFullScreenMode(true);
        fT = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);
        fS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fhT = fT.getHeight();
        fhS = fS.getHeight();
    }

    // --- List-like API ---

    public void append(String label) { items.addElement(label != null ? label : ""); }

    public void set(int idx, String label) {
        if (idx >= 0 && idx < items.size()) items.setElementAt(label != null ? label : "", idx);
    }

    public String getString(int idx) {
        if (idx < 0 || idx >= items.size()) return "";
        return (String) items.elementAt(idx);
    }

    public int size() { return items.size(); }

    public int getSelectedIndex() { return items.size() == 0 ? -1 : sel; }

    public void setSelectedIndex(int idx) {
        if (idx >= 0 && idx < items.size()) { sel = idx; ensureVisible(); }
    }

    /** Command sent to the listener on OK/FIRE - like SELECT_COMMAND on a List. */
    public void setSelectCommand(Command c) { selectCmd = c; }

    public void setCommandListener(CommandListener l) {
        listener = l;
        super.setCommandListener(l);
    }

    /** Reset the 0 repeat block - common cleanup on panic (enterMask). */
    public void resetPanic() { panicFired = false; }

    // --- DRAWING ---

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(TerminalCanvas.COLOR_BG);
        g.fillRect(0, 0, w, h);

        // Header
        g.setFont(fT);
        g.setColor(TerminalCanvas.COLOR_ACCENT);
        g.drawString(clip(header, fT, w - PAD * 2), PAD, PAD, Graphics.TOP | Graphics.LEFT);
        int headerH = PAD + fhT + 4;
        g.setColor(TerminalCanvas.COLOR_BORDER);
        g.drawLine(0, headerH, w, headerH);

        int rowH = fhS + ROW_PAD * 2;
        int listY = headerH + 3;
        rowsVisible = (h - listY - PAD) / rowH;
        if (rowsVisible < 1) rowsVisible = 1;
        ensureVisible();

        g.setFont(fS);
        int n = items.size();
        for (int i = 0; i < rowsVisible && top + i < n; i++) {
            int idx = top + i;
            int y = listY + i * rowH;
            String label = (String) items.elementAt(idx);
            if (idx == sel) {
                g.setColor(TerminalCanvas.COLOR_ACCENT);
                g.fillRect(0, y, w, rowH);
                g.setColor(TerminalCanvas.COLOR_BG);
            } else {
                g.setColor(TerminalCanvas.COLOR_TEXT);
            }
            g.drawString(clip(label, fS, w - PAD * 2), PAD, y + ROW_PAD, Graphics.TOP | Graphics.LEFT);
        }

        // "more" strip at top/bottom so a long list does not look truncated
        g.setColor(TerminalCanvas.COLOR_DIM);
        if (top > 0) g.drawString("^", w - PAD, listY, Graphics.TOP | Graphics.RIGHT);
        if (top + rowsVisible < n)
            g.drawString("v", w - PAD, listY + (rowsVisible - 1) * rowH + ROW_PAD,
                         Graphics.TOP | Graphics.RIGHT);
    }

    /** Truncate to width with an ellipsis: a long name must not run past the edge. */
    private String clip(String s, Font f, int maxW) {
        if (s == null) return "";
        if (f.stringWidth(s) <= maxW) return s;
        int dots = f.stringWidth("..");
        int end = s.length();
        while (end > 0 && f.stringWidth(s.substring(0, end)) + dots > maxW) end--;
        return s.substring(0, end) + "..";
    }

    private void ensureVisible() {
        int n = items.size();
        if (n == 0) { sel = 0; top = 0; return; }
        if (sel < 0) sel = 0;
        if (sel >= n) sel = n - 1;
        if (sel < top) top = sel;
        if (sel >= top + rowsVisible) top = sel - rowsVisible + 1;
        if (top < 0) top = 0;
    }

    // --- INPUT ---

    protected void keyPressed(int keyCode) {
        // short 0 does nothing: the key is reserved for panic (hold)
        if (keyCode == KEY_NUM0) { panicFired = false; return; }
        if (keyCode == KEY_NUM2) { move(-1); return; }
        if (keyCode == KEY_NUM8) { move(1); return; }
        if (keyCode == KEY_NUM5) { fire(); return; }
        int ga = getGameAction(keyCode);
        if (ga == UP) move(-1);
        else if (ga == DOWN) move(1);
        else if (ga == FIRE) fire();
    }

    protected void keyRepeated(int keyCode) {
        // HELD 0 is panic: works in the menu, contacts and settings
        if (keyCode == KEY_NUM0) {
            if (!panicFired) { panicFired = true; midlet.onPanicHold(); }
            return;
        }
        // Holding up/down scrolls the list
        if (keyCode == KEY_NUM2) { move(-1); return; }
        if (keyCode == KEY_NUM8) { move(1); return; }
        int ga = getGameAction(keyCode);
        if (ga == UP) move(-1);
        else if (ga == DOWN) move(1);
    }

    protected void keyReleased(int keyCode) {
        if (keyCode == KEY_NUM0) panicFired = false;
    }

    private void move(int delta) {
        int n = items.size();
        if (n == 0) return;
        sel = (sel + delta + n) % n;
        ensureVisible();
        repaint();
    }

    private void fire() {
        if (listener != null && selectCmd != null) listener.commandAction(selectCmd, this);
    }
}
