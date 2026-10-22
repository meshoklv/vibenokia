package gemswap;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Gem Swap: swap neighbouring gems to line up three or more of a kind. */
public class GemGame extends Game {
    private static final int N = 8, KINDS = 6;
    private static final int[] COL = { 0xE53935, 0x43A047, 0x1E88E5, 0xFDD835, 0x8E24AA, 0xFB8C00 };
    private static final int IDLE = 0, SELECTED = 1, SWAPPING = 2, CLEARING = 3, FALLING = 4, SWAP_BACK = 5;

    private final int[] b = new int[N * N];
    private final boolean[] clear = new boolean[N * N];
    private final int[] fallOff = new int[N * N];
    private int cur = 27, phase, sel, other, anim, movesLeft, timeLeft, chain, hintT, hintA = -1, hintB;

    protected String name() { return "Gem Swap"; }

    protected String[] help() {
        return new String[] {
            "Swap two neighbouring gems to make a line of three or more of the same colour. Matched gems vanish, the gems above fall down, and new ones drop in.",
            "Chain reactions score extra. Longer lines score more.",
            "Moves mode: 30 swaps. Timed mode: 90 seconds. If you're stuck, a hint will glow after a while.",
            "- Controls",
            "2/4/6/8: move cursor",
            "5: pick gem, then 2/4/6/8: swap",
        };
    }

    protected String[] modes() { return new String[] { "30 Moves", "90 Seconds" }; }

    protected int accent() { return 0xE53935; }

    protected void newGame() {
        do {
            for (int i = 0; i < N * N; i++) {
                do {
                    b[i] = Rnd.nextInt(KINDS);
                } while (makesMatch(i));
            }
        } while (!hasMove());
        phase = IDLE;
        movesLeft = 30;
        timeLeft = 90 * 20;
        chain = 0;
        hintT = 0;
        hintA = -1;
    }

    private boolean makesMatch(int i) {
        int x = i % N, y = i / N, v = b[i];
        if (x >= 2 && b[i - 1] == v && b[i - 2] == v) return true;
        return y >= 2 && b[i - N] == v && b[i - 2 * N] == v;
    }

    private int findMatches() {
        for (int i = 0; i < N * N; i++) clear[i] = false;
        int n = 0;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int v = b[y * N + x];
                if (v < 0) continue;
                int len = 1;
                while (x + len < N && b[y * N + x + len] == v) len++;
                if (len >= 3) {
                    for (int k = 0; k < len; k++) clear[y * N + x + k] = true;
                    score += (len - 2) * 30 * (chain + 1);
                }
                len = 1;
                while (y + len < N && b[(y + len) * N + x] == v) len++;
                if (len >= 3) {
                    for (int k = 0; k < len; k++) clear[(y + k) * N + x] = true;
                    score += (len - 2) * 30 * (chain + 1);
                }
            }
        }
        for (int i = 0; i < N * N; i++) if (clear[i]) n++;
        return n;
    }

    private void swap(int a, int c) {
        int t = b[a];
        b[a] = b[c];
        b[c] = t;
    }

    private boolean matchAt() {
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int v = b[y * N + x];
                if (x + 2 < N && b[y * N + x + 1] == v && b[y * N + x + 2] == v) return true;
                if (y + 2 < N && b[(y + 1) * N + x] == v && b[(y + 2) * N + x] == v) return true;
            }
        }
        return false;
    }

    private boolean hasMove() {
        for (int i = 0; i < N * N; i++) {
            int x = i % N;
            if (x + 1 < N) {
                swap(i, i + 1);
                boolean m = matchAt();
                swap(i, i + 1);
                if (m) { hintA = i; hintB = i + 1; return true; }
            }
            if (i + N < N * N) {
                swap(i, i + N);
                boolean m = matchAt();
                swap(i, i + N);
                if (m) { hintA = i; hintB = i + N; return true; }
            }
        }
        return false;
    }

    private void collapse() {
        for (int x = 0; x < N; x++) {
            int dst = N - 1;
            for (int y = N - 1; y >= 0; y--) {
                int i = y * N + x;
                if (clear[i]) continue;
                int d = dst * N + x;
                b[d] = b[i];
                fallOff[d] = dst - y;
                dst--;
            }
            for (int y = dst; y >= 0; y--) {
                b[y * N + x] = Rnd.nextInt(KINDS);
                fallOff[y * N + x] = dst + 1;
            }
        }
    }

    protected void update() {
        if (mode == 1 && phase == IDLE || mode == 1 && phase == SELECTED) {
            if (--timeLeft <= 0) {
                endGame(true);
                return;
            }
        }
        switch (phase) {
            case IDLE:
            case SELECTED: {
                hintT++;
                int dir = -1;
                if ((pressed & K_UP) != 0) dir = 0;
                else if ((pressed & K_DOWN) != 0) dir = 1;
                else if ((pressed & K_LEFT) != 0) dir = 2;
                else if ((pressed & K_RIGHT) != 0) dir = 3;
                if (phase == IDLE) {
                    if (dir >= 0) cur = step(cur, dir);
                    if ((pressed & K_FIRE) != 0) {
                        phase = SELECTED;
                        sel = cur;
                        Sfx.click();
                    }
                } else {
                    if ((pressed & K_FIRE) != 0 || digit(0)) phase = IDLE;
                    else if (dir >= 0) {
                        int t = step(sel, dir);
                        if (t != sel) {
                            other = t;
                            cur = t;
                            swap(sel, other);
                            phase = SWAPPING;
                            anim = 4;
                            hintT = 0;
                        }
                    }
                }
                break;
            }
            case SWAPPING:
                if (--anim > 0) break;
                chain = 0;
                if (findMatches() > 0) {
                    phase = CLEARING;
                    anim = 6;
                    if (mode == 0) movesLeft--;
                    Sfx.good();
                } else {
                    swap(sel, other);
                    phase = SWAP_BACK;
                    anim = 4;
                    Sfx.bad();
                }
                break;
            case SWAP_BACK:
                if (--anim <= 0) phase = IDLE;
                break;
            case CLEARING:
                if (--anim > 0) break;
                collapse();
                phase = FALLING;
                anim = 5;
                break;
            default: // FALLING
                if (--anim > 0) break;
                for (int i = 0; i < N * N; i++) fallOff[i] = 0;
                chain++;
                if (findMatches() > 0) {
                    phase = CLEARING;
                    anim = 6;
                    Sfx.tone(80 + chain * 3, 40);
                } else {
                    phase = IDLE;
                    if (!hasMove()) {
                        newBoardKeepScore();
                    }
                    if (mode == 0 && movesLeft <= 0) endGame(true);
                }
                break;
        }
    }

    private void newBoardKeepScore() {
        int s = score;
        int m = movesLeft, t = timeLeft;
        newGame();
        score = s;
        movesLeft = m;
        timeLeft = t;
    }

    private int step(int i, int dir) {
        int x = i % N, y = i / N;
        if (dir == 0 && y > 0) y--;
        else if (dir == 1 && y < N - 1) y++;
        else if (dir == 2 && x > 0) x--;
        else if (dir == 3 && x < N - 1) x++;
        return y * N + x;
    }

    private void gem(Graphics g, int v, int x, int y, int s) {
        int c = COL[v], m = Math.max(1, s / 8);
        switch (v) {
            case 0:
                g.setColor(c);
                g.fillTriangle(x + s / 2, y + m, x + m, y + s / 2, x + s - m, y + s / 2);
                g.fillTriangle(x + s / 2, y + s - m, x + m, y + s / 2, x + s - m, y + s / 2);
                break;
            case 1:
                g.setColor(c);
                g.fillRect(x + m * 2, y + m * 2, s - m * 4, s - m * 4);
                break;
            case 2:
                g.setColor(c);
                Gfx.disc(g, x + s / 2, y + s / 2, s / 2 - m);
                break;
            case 3:
                g.setColor(c);
                g.fillTriangle(x + s / 2, y + m, x + m, y + s - m, x + s - m, y + s - m);
                break;
            case 4:
                g.setColor(c);
                g.fillRoundRect(x + m, y + m * 2, s - m * 2, s - m * 4, s / 2, s / 2);
                break;
            default:
                g.setColor(c);
                g.fillTriangle(x + s / 2, y + m, x + m, y + s / 3, x + s - m, y + s / 3);
                g.fillRect(x + m, y + s / 3, s - m * 2, s / 3);
                g.fillTriangle(x + m, y + s * 2 / 3, x + s - m, y + s * 2 / 3, x + s / 2, y + s - m);
                break;
        }
        g.setColor(0xFFFFFF);
        g.fillRect(x + s / 3, y + s / 3, Math.max(1, s / 8), Math.max(1, s / 8));
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int s = Math.max(8, Math.min(W - 4, H - hud * 2) / N);
        int ox = (W - s * N) / 2, oy = hud + (H - hud * 2 - s * N) / 2;
        g.setColor(0x1A1033);
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < N * N; i++) {
            g.setColor(((i % N) + (i / N)) % 2 == 0 ? 0x2A1F4D : 0x241A42);
            g.fillRect(ox + (i % N) * s, oy + (i / N) * s, s, s);
        }
        for (int i = 0; i < N * N; i++) {
            if (b[i] < 0) continue;
            int x = ox + (i % N) * s, y = oy + (i / N) * s;
            if (phase == CLEARING && clear[i]) {
                if ((anim & 1) == 0) continue;
            }
            if (phase == FALLING) y -= fallOff[i] * s * anim / 5;
            if ((phase == SWAPPING || phase == SWAP_BACK) && (i == sel || i == other)) {
                int from = i == sel ? other : sel;
                int fx = ox + (from % N) * s, fy = oy + (from / N) * s;
                x = x + (fx - x) * anim / 4;
                y = y + (fy - y) * anim / 4;
            }
            gem(g, b[i], x, y, s);
        }
        g.setClip(0, 0, W, H);
        if (phase == SELECTED) {
            g.setColor(0xFFFFFF);
            g.drawRect(ox + (sel % N) * s, oy + (sel / N) * s, s - 1, s - 1);
            g.drawRect(ox + (sel % N) * s + 1, oy + (sel / N) * s + 1, s - 3, s - 3);
        } else if (phase == IDLE) {
            g.setColor((clock & 4) == 0 ? 0xFFEB3B : 0xFFA000);
            g.drawRect(ox + (cur % N) * s, oy + (cur / N) * s, s - 1, s - 1);
            if (hintT > 200 && hintA >= 0 && (clock & 8) == 0) {
                g.setColor(0x80DEEA);
                g.drawRect(ox + (hintA % N) * s + 2, oy + (hintA / N) * s + 2, s - 5, s - 5);
                g.drawRect(ox + (hintB % N) * s + 2, oy + (hintB / N) * s + 2, s - 5, s - 5);
            }
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(score), 2, 1, Gfx.TL);
        g.setColor(0xF48FB1);
        g.drawString(mode == 0 ? movesLeft + " moves" : (timeLeft / 20) + "s", W - 2, 1, Gfx.TR);
        if (chain > 1 && phase != IDLE) Gfx.text(g, "CHAIN x" + chain, W / 2, H - hud + 1, Gfx.TC, Gfx.SMALL_B, 0xFFEB3B);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int s = Math.max(8, Math.min(h - 4, w / 7));
        int n = Math.min(6, w / s);
        int x0 = x + (w - n * s) / 2;
        for (int k = 0; k < n; k++) {
            int bob = ((clock / 4 + k) % 8 == 0) ? -3 : 0;
            gem(g, k % KINDS, x0 + k * s, y + (h - s) / 2 + bob, s);
        }
    }
}
