package seedsowing;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Seed Sowing: a count-and-capture board game in the mancala family (Kalah
 * rules). Pits 0-5 are yours, 6 your store, 7-12 the opponent's, 13 theirs.
 */
public class SowGame extends Game {
    private final int[] b = new int[14];
    private final int[][] stack = new int[12][14];
    private int turn, sel, sowPit = -1, sowLeft, sowWait, cpuWait, lastPit = -1;
    private String note = "";

    protected String name() { return "Seed Sowing"; }

    protected String[] help() {
        return new String[] {
            "Pick one of your six pits (bottom row): its seeds are sown one by one into the following pits anticlockwise, including your store on the right but skipping the opponent's.",
            "If your last seed lands in your store, you move again. If it lands in an empty pit on your side, you capture it and all the seeds in the pit opposite.",
            "When one side runs out of seeds the game ends; the player with more seeds in their store wins.",
            "- Controls",
            "1-6: sow that pit (left to right)",
            "4/6 + 5: choose with the joystick",
        };
    }

    protected String[] modes() { return new String[] { "4 seeds vs CPU", "6 seeds vs CPU", "4 seeds 2 players" }; }

    protected boolean hasScore() { return mode < 2; }

    protected String formatScore(int s) { return s + " seeds"; }

    protected int accent() { return 0xA47551; }

    private boolean cpu() { return mode < 2; }

    protected void newGame() {
        int seeds = mode == 1 ? 6 : 4;
        for (int i = 0; i < 14; i++) b[i] = (i == 6 || i == 13) ? 0 : seeds;
        turn = 0;
        sel = 0;
        sowPit = -1;
        lastPit = -1;
        note = "";
    }

    private static int store(int side) { return side == 0 ? 6 : 13; }

    /** Apply a full move on board a; returns true if the same side moves again. */
    private static boolean move(int[] a, int pit, int side) {
        int seeds = a[pit];
        a[pit] = 0;
        int i = pit;
        while (seeds > 0) {
            i = (i + 1) % 14;
            if (i == store(1 - side)) continue;
            a[i]++;
            seeds--;
        }
        if (i == store(side)) return true;
        boolean mine = side == 0 ? i < 6 : (i > 6 && i < 13);
        if (mine && a[i] == 1 && a[12 - i] > 0) {
            a[store(side)] += a[12 - i] + 1;
            a[i] = 0;
            a[12 - i] = 0;
        }
        return false;
    }

    private static boolean sideEmpty(int[] a, int side) {
        int s = side == 0 ? 0 : 7;
        for (int k = 0; k < 6; k++) if (a[s + k] > 0) return false;
        return true;
    }

    private int search(int[] a, int side, int depth, int alpha, int beta, int ply) {
        if (depth == 0 || sideEmpty(a, 0) || sideEmpty(a, 1)) {
            int v = a[13] - a[6];
            if (sideEmpty(a, 0) || sideEmpty(a, 1)) {
                int rest0 = 0, rest1 = 0;
                for (int k = 0; k < 6; k++) { rest0 += a[k]; rest1 += a[7 + k]; }
                v = (a[13] + rest1) - (a[6] + rest0);
            }
            return side == 1 ? v : -v;
        }
        int[] nb = stack[ply];
        int best = -1000;
        int base = side == 0 ? 0 : 7;
        for (int k = 0; k < 6; k++) {
            if (a[base + k] == 0) continue;
            System.arraycopy(a, 0, nb, 0, 14);
            boolean again = move(nb, base + k, side);
            int v = again ? search(nb, side, depth - 1, alpha, beta, ply + 1) : -search(nb, 1 - side, depth - 1, -beta, -alpha, ply + 1);
            if (v > best) best = v;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;
        }
        return best;
    }

    private int cpuPick() {
        int depth = mode == 0 ? 4 : 7;
        int best = -1000, bestK = 7;
        int[] nb = new int[14];
        for (int k = 0; k < 6; k++) {
            if (b[7 + k] == 0) continue;
            System.arraycopy(b, 0, nb, 0, 14);
            boolean again = move(nb, 7 + k, 1);
            int v = again ? search(nb, 1, depth - 1, -1000, 1000, 0) : -search(nb, 0, depth - 1, -1000, 1000, 0);
            v = v * 4 + Rnd.nextInt(mode == 0 ? 6 : 2);
            if (v > best) {
                best = v;
                bestK = 7 + k;
            }
        }
        return bestK;
    }

    private void startSow(int pit) {
        sowPit = pit;
        sowLeft = b[pit];
        b[pit] = 0;
        lastPit = pit;
        sowWait = 0;
        Sfx.click();
    }

    private void stepSow() {
        if (--sowWait > 0) return;
        sowWait = 3;
        int i = lastPit;
        do {
            i = (i + 1) % 14;
        } while (i == store(1 - turn));
        b[i]++;
        lastPit = i;
        sowLeft--;
        Sfx.tone(60 + (i % 7) * 2, 15);
        if (sowLeft > 0) return;
        sowPit = -1;
        // finish: extra turn or capture, using the same rule as move()
        boolean again = i == store(turn);
        boolean mine = turn == 0 ? i < 6 : (i > 6 && i < 13);
        if (!again && mine && b[i] == 1 && b[12 - i] > 0) {
            int cap = b[12 - i] + 1;
            b[store(turn)] += cap;
            b[i] = 0;
            b[12 - i] = 0;
            note = (turn == 0 ? (cpu() ? "You capture " : "South captures ") : (cpu() ? "CPU captures " : "North captures ")) + cap + "!";
            Sfx.good();
        } else if (again) {
            note = turn == 0 ? (cpu() ? "Free turn!" : "South goes again") : (cpu() ? "CPU goes again" : "North goes again");
        } else {
            note = "";
        }
        if (sideEmpty(b, 0) || sideEmpty(b, 1)) {
            for (int k = 0; k < 6; k++) {
                b[6] += b[k];
                b[13] += b[7 + k];
                b[k] = b[7 + k] = 0;
            }
            score = b[6];
            if (b[6] == b[13]) {
                headline = "DRAW " + b[6] + "-" + b[13];
                endGame(false);
            } else {
                boolean southWins = b[6] > b[13];
                headline = (cpu() ? (southWins ? "YOU WIN " : "CPU WINS ") : (southWins ? "SOUTH WINS " : "NORTH WINS ")) + b[6] + "-" + b[13];
                endGame(cpu() ? southWins : true);
            }
            return;
        }
        if (!again) turn = 1 - turn;
    }

    protected void update() {
        if (sowPit >= 0) {
            stepSow();
            return;
        }
        boolean human = !cpu() || turn == 0;
        if (!human) {
            if (++cpuWait < 12) return;
            cpuWait = 0;
            startSow(cpuPick());
            return;
        }
        int d = digitPressed();
        int pick = -1;
        if (d >= 1 && d <= 6) pick = turn == 0 ? d - 1 : 13 - d;
        else if (d < 0) {
            if ((pressed & K_LEFT) != 0) sel = (sel + 5) % 6;
            if ((pressed & K_RIGHT) != 0) sel = (sel + 1) % 6;
            if ((pressed & K_FIRE) != 0) pick = turn == 0 ? sel : 12 - sel;
        }
        if (pick >= 0) {
            if (b[pick] == 0) Sfx.bad();
            else startSow(pick);
        }
    }

    private int pitX(int i, int pw, int ox) {
        if (i < 6) return ox + pw + i * pw;
        return ox + pw + (12 - i) * pw;
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        g.setColor(0x2B1B10);
        g.fillRect(0, 0, W, H);
        int pw = (W - 6) / 8;
        int ox = (W - pw * 8) / 2;
        int boardH = Math.min(H - hud * 4, pw * 3);
        int oy = hud * 2 + (H - hud * 4 - boardH) / 2;
        g.setColor(0x8D6E63);
        g.fillRoundRect(ox, oy, pw * 8, boardH, 12, 12);
        g.setColor(0x6D4C41);
        g.drawRoundRect(ox, oy, pw * 8 - 1, boardH - 1, 12, 12);
        int rowTop = oy + boardH / 4, rowBot = oy + boardH * 3 / 4;
        int r = Math.max(5, Math.min(pw, boardH / 2) / 2 - 2);
        for (int i = 0; i < 14; i++) {
            int x, y, rr = r;
            if (i == 6) { x = ox + pw * 7 + pw / 2; y = oy + boardH / 2; }
            else if (i == 13) { x = ox + pw / 2; y = oy + boardH / 2; }
            else { x = pitX(i, pw, ox) + pw / 2; y = i < 6 ? rowBot : rowTop; }
            g.setColor(0x4E342E);
            if (i == 6 || i == 13) g.fillRoundRect(x - pw / 2 + 2, oy + 4, pw - 4, boardH - 8, pw / 2, pw / 2);
            else Gfx.disc(g, x, y, rr);
            boolean selected = sowPit < 0 && state == PLAY && ((turn == 0 && i == sel) || (!cpu() && turn == 1 && i == 12 - sel));
            if (selected) {
                g.setColor((clock & 4) == 0 ? 0xFFEB3B : 0xFFFFFF);
                Gfx.ring(g, x, y, rr + 1);
            }
            if (i == lastPit && sowPit >= 0) {
                g.setColor(0xFFB300);
                Gfx.ring(g, x, y, rr);
            }
            // seeds as dots, then the count
            int n = b[i];
            g.setColor(0xFFF59D);
            for (int k = 0; k < Math.min(n, 8); k++) g.fillRect(x - 4 + (k % 4) * 3, y - 3 + (k / 4) * 3, 2, 2);
            g.setFont(Gfx.SMALL_B);
            g.setColor(0xFFFFFF);
            int ly = (i == 6 || i == 13) ? oy + boardH - Gfx.SMALL.getHeight() - 4 : (i < 6 ? y + rr : y - rr - Gfx.SMALL.getHeight() + 1);
            g.drawString(String.valueOf(n), x, ly, Gfx.TC);
            if (i < 6) {
                g.setFont(Gfx.SMALL);
                g.setColor(0xBCAAA4);
                g.drawString(String.valueOf(i + 1), x, oy + boardH + 2, Gfx.TC);
            }
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFE0B2);
        g.drawString((cpu() ? "You " : "South ") + b[6], W - 2, 1, Gfx.TR);
        g.drawString((cpu() ? "CPU " : "North ") + b[13], 2, 1, Gfx.TL);
        String t = sowPit >= 0 ? "Sowing..." : (turn == 0 ? (cpu() ? "Your turn" : "South's turn") : (cpu() ? "CPU thinking..." : "North's turn"));
        Gfx.text(g, note.length() > 0 ? note : t, W / 2, H - hud, Gfx.TC, Gfx.SMALL, 0xFFCC80);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int pw = Math.max(10, w / 7);
        g.setColor(0x8D6E63);
        g.fillRoundRect(x + (w - pw * 6) / 2, y + (h - pw) / 2, pw * 6, pw, 10, 10);
        for (int k = 0; k < 6; k++) {
            int cx = x + (w - pw * 6) / 2 + k * pw + pw / 2, cy = y + h / 2;
            g.setColor(0x4E342E);
            Gfx.disc(g, cx, cy, pw / 2 - 2);
            int seeds = ((clock / 8) + k * 3) % 5;
            g.setColor(0xFFF59D);
            for (int s = 0; s < seeds; s++) g.fillRect(cx - 3 + (s % 3) * 3, cy - 2 + (s / 3) * 3, 2, 2);
        }
    }
}
