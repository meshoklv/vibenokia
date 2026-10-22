package gamekit;

/**
 * Character tile map with axis-separated AABB collision, for platformers
 * and maze games. Positions handed to Body are in 1/256 pixel units.
 *
 * Tile meaning is decided by the game through two strings:
 *   solids   tiles that block from every side (default "#")
 *   oneWay   tiles you can jump through from below (default "=")
 * Outside the left/right edges counts as solid; above and below is empty.
 */
public final class TileMap {
    public final int cols, rows;
    public final char[] t;
    public int ts = 8;
    public String solids = "#";
    public String oneWay = "=";

    public TileMap(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        t = new char[cols * rows];
        for (int i = 0; i < t.length; i++) t[i] = ' ';
    }

    /** Build from rows of text; short rows are padded with their last character. */
    public TileMap(String[] lines) {
        int w = 0;
        for (int i = 0; i < lines.length; i++) w = Math.max(w, lines[i].length());
        cols = w;
        rows = lines.length;
        t = new char[cols * rows];
        for (int r = 0; r < rows; r++) {
            String s = lines[r];
            char pad = s.length() > 0 ? s.charAt(s.length() - 1) : ' ';
            for (int c = 0; c < cols; c++) t[r * cols + c] = c < s.length() ? s.charAt(c) : pad;
        }
    }

    public char get(int c, int r) {
        if (c < 0 || c >= cols) return '#';
        if (r < 0 || r >= rows) return ' ';
        return t[r * cols + c];
    }

    public void set(int c, int r, char ch) {
        if (c >= 0 && c < cols && r >= 0 && r < rows) t[r * cols + c] = ch;
    }

    public boolean isSolid(int c, int r) {
        char ch = get(c, r);
        return solids.indexOf(ch) >= 0;
    }

    public boolean isOneWay(int c, int r) {
        return oneWay.indexOf(get(c, r)) >= 0;
    }

    /** Floor division by the tile size (works for negative pixels). */
    public int tile(int px) {
        return px >= 0 ? px / ts : (px - ts + 1) / ts;
    }

    /** Find the first tile of a character; returns c | (r << 16) or -1. */
    public int find(char ch) {
        for (int i = 0; i < t.length; i++) if (t[i] == ch) return (i % cols) | ((i / cols) << 16);
        return -1;
    }
}
