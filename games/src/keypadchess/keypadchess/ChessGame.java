package keypadchess;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Keypad Chess: full rules (castling, en passant, promotion, check, mate,
 * stalemate, 50-move rule) against an alpha-beta AI. Player is White.
 */
public class ChessGame extends Game {
    static final int P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6;
    private static final int[] VALUE = { 0, 100, 320, 330, 500, 900, 0 };
    private static final int MATE = 100000;

    // piece-square bonuses from White's point of view (index 0 = a8)
    private static final byte[] PST_P = {
        0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0 };
    private static final byte[] PST_N = {
        -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50 };
    private static final byte[] PST_K = {
        -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30, -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20 };

    private static final int[] KN = { -17, -15, -10, -6, 6, 10, 15, 17 };
    private static final int[] KD = { -9, -8, -7, -1, 1, 7, 8, 9 };

    final int[] b = new int[64];
    int side, castle, ep, half;
    private final int[] kingSq = new int[2];

    // undo stack
    private final int[] uCap = new int[64], uCastle = new int[64], uEp = new int[64], uHalf = new int[64], uMove = new int[64];
    private int sp;

    private final int[][] moveBuf = new int[8][256];
    private final int[] scoreBuf = new int[256];

    private int curX = 4, curY = 6, sel = -1, lastFrom = -1, lastTo = -1;
    private int[] legal = new int[256];
    private int nLegal;
    private boolean thinking;
    private int thinkDelay;
    private Image[] sprites;
    private int spriteScale;

    protected String name() { return "Keypad Chess"; }

    protected String[] help() {
        return new String[] {
            "Classic chess. You play White against the phone. All rules are supported: castling, en passant, promotion, check, checkmate, stalemate and the 50-move rule.",
            "Move the cursor to one of your pieces and press 5. Its legal moves are marked; move the cursor to a target and press 5 again. Pawns promote to a queen.",
            "To castle, move the king two squares.",
            "- Controls",
            "2/4/6/8: move cursor  1/3/7/9: diagonal",
            "5: select / move  0: cancel",
        };
    }

    protected String[] modes() { return new String[] { "Easy", "Normal", "Hard" }; }

    protected boolean hasScore() { return false; }

    protected String footer() { return "Won " + saved[0] + "  Lost " + saved[1] + "  Draw " + saved[2]; }

    protected int accent() { return 0xD9A441; }

    protected void newGame() {
        String back = "RNBQKBNR";
        for (int i = 0; i < 64; i++) b[i] = 0;
        for (int c = 0; c < 8; c++) {
            int t = "PNBRQK".indexOf(back.charAt(c)) + 1;
            b[c] = -t;
            b[56 + c] = t;
            b[8 + c] = -P;
            b[48 + c] = P;
        }
        side = 1;
        castle = 15;
        ep = -1;
        half = 0;
        sp = 0;
        kingSq[0] = 60;
        kingSq[1] = 4;
        sel = -1;
        lastFrom = lastTo = -1;
        curX = 4;
        curY = 6;
        thinking = false;
        refreshLegal();
    }

    // ------------------------------------------------------------ rules

    private static int ki(int s) { return s > 0 ? 0 : 1; }

    boolean attacked(int sq, int by) {
        int r = sq >> 3, c = sq & 7;
        // pawns
        int pr = r + (by > 0 ? 1 : -1);
        if (pr >= 0 && pr < 8) {
            if (c > 0 && b[pr * 8 + c - 1] == by * P) return true;
            if (c < 7 && b[pr * 8 + c + 1] == by * P) return true;
        }
        for (int i = 0; i < 8; i++) {
            int t = sq + KN[i];
            if (t >= 0 && t < 64 && Math.abs((t & 7) - c) <= 2 && b[t] == by * N) return true;
            int t2 = sq + KD[i];
            if (t2 >= 0 && t2 < 64 && Math.abs((t2 & 7) - c) <= 1 && b[t2] == by * K) return true;
        }
        for (int i = 0; i < 8; i++) {
            int d = KD[i];
            boolean diag = (i == 0 || i == 2 || i == 5 || i == 7);
            int s = sq, pc = c;
            while (true) {
                s += d;
                if (s < 0 || s >= 64 || Math.abs((s & 7) - pc) > 1) break;
                pc = s & 7;
                int v = b[s];
                if (v == 0) continue;
                if (v * by > 0) {
                    int t = v * by;
                    if (t == Q || (diag ? t == B : t == R)) return true;
                }
                break;
            }
        }
        return false;
    }

    boolean inCheck(int s) {
        return attacked(kingSq[ki(s)], -s);
    }

    private static int mv(int from, int to, int flag) { return from | (to << 6) | (flag << 12); }

    /** Generate pseudo-legal moves for side s. flags: 1 ep, 2 castle, 4 double, 8 promo. */
    int gen(int s, int[] out, boolean capturesOnly) {
        int n = 0;
        for (int sq = 0; sq < 64; sq++) {
            int v = b[sq];
            if (v * s <= 0) continue;
            int t = v * s, r = sq >> 3, c = sq & 7;
            if (t == P) {
                int dir = s > 0 ? -8 : 8;
                int to = sq + dir;
                int lastRank = s > 0 ? 0 : 7;
                if (!capturesOnly && to >= 0 && to < 64 && b[to] == 0) {
                    out[n++] = mv(sq, to, (to >> 3) == lastRank ? 8 : 0);
                    int start = s > 0 ? 6 : 1;
                    if (r == start && b[to + dir] == 0) out[n++] = mv(sq, to + dir, 4);
                }
                for (int dc = -1; dc <= 1; dc += 2) {
                    if (c + dc < 0 || c + dc > 7) continue;
                    int cap = to + dc;
                    if (cap < 0 || cap >= 64) continue;
                    if (b[cap] * s < 0) out[n++] = mv(sq, cap, (cap >> 3) == lastRank ? 8 : 0);
                    else if (cap == ep) out[n++] = mv(sq, cap, 1);
                }
            } else if (t == N || t == K) {
                int[] d = t == N ? KN : KD;
                int lim = t == N ? 2 : 1;
                for (int i = 0; i < 8; i++) {
                    int to = sq + d[i];
                    if (to < 0 || to >= 64 || Math.abs((to & 7) - c) > lim) continue;
                    if (b[to] * s > 0) continue;
                    if (capturesOnly && b[to] == 0) continue;
                    out[n++] = mv(sq, to, 0);
                }
                if (t == K && !capturesOnly) {
                    int home = s > 0 ? 60 : 4;
                    int kb = s > 0 ? 1 : 4, qb = s > 0 ? 2 : 8;
                    if (sq == home && !attacked(home, -s)) {
                        if ((castle & kb) != 0 && b[home + 1] == 0 && b[home + 2] == 0 && b[home + 3] == s * R
                                && !attacked(home + 1, -s) && !attacked(home + 2, -s)) out[n++] = mv(sq, home + 2, 2);
                        if ((castle & qb) != 0 && b[home - 1] == 0 && b[home - 2] == 0 && b[home - 3] == 0 && b[home - 4] == s * R
                                && !attacked(home - 1, -s) && !attacked(home - 2, -s)) out[n++] = mv(sq, home - 2, 2);
                    }
                }
            } else {
                for (int i = 0; i < 8; i++) {
                    boolean diag = (i == 0 || i == 2 || i == 5 || i == 7);
                    if (t == B && !diag) continue;
                    if (t == R && diag) continue;
                    int d = KD[i], to = sq, pc = c;
                    while (true) {
                        to += d;
                        if (to < 0 || to >= 64 || Math.abs((to & 7) - pc) > 1) break;
                        pc = to & 7;
                        if (b[to] * s > 0) break;
                        if (b[to] != 0) {
                            out[n++] = mv(sq, to, 0);
                            break;
                        }
                        if (!capturesOnly) out[n++] = mv(sq, to, 0);
                    }
                }
            }
        }
        return n;
    }

    void make(int m) {
        int from = m & 63, to = (m >> 6) & 63, flag = m >> 12;
        int pc = b[from], s = pc > 0 ? 1 : -1;
        uMove[sp] = m;
        uCap[sp] = b[to];
        uCastle[sp] = castle;
        uEp[sp] = ep;
        uHalf[sp] = half;
        sp++;
        half = (Math.abs(pc) == P || b[to] != 0) ? 0 : half + 1;
        b[to] = pc;
        b[from] = 0;
        if ((flag & 1) != 0) {
            int capSq = to + (s > 0 ? 8 : -8);
            uCap[sp - 1] = b[capSq];
            b[capSq] = 0;
        }
        if ((flag & 8) != 0) b[to] = s * Q;
        if ((flag & 2) != 0) {
            if ((to & 7) == 6) { b[to - 1] = b[to + 1]; b[to + 1] = 0; }
            else { b[to + 1] = b[to - 2]; b[to - 2] = 0; }
        }
        ep = (flag & 4) != 0 ? (from + to) / 2 : -1;
        if (Math.abs(pc) == K) {
            kingSq[ki(s)] = to;
            castle &= s > 0 ? ~3 : ~12;
        }
        if (from == 63 || to == 63) castle &= ~1;
        if (from == 56 || to == 56) castle &= ~2;
        if (from == 7 || to == 7) castle &= ~4;
        if (from == 0 || to == 0) castle &= ~8;
        side = -s;
    }

    void unmake() {
        sp--;
        int m = uMove[sp];
        int from = m & 63, to = (m >> 6) & 63, flag = m >> 12;
        int pc = b[to], s = pc > 0 ? 1 : -1;
        if ((flag & 8) != 0) pc = s * P;
        b[from] = pc;
        if ((flag & 1) != 0) {
            b[to] = 0;
            b[to + (s > 0 ? 8 : -8)] = uCap[sp];
        } else {
            b[to] = uCap[sp];
        }
        if ((flag & 2) != 0) {
            if ((to & 7) == 6) { b[to + 1] = b[to - 1]; b[to - 1] = 0; }
            else { b[to - 2] = b[to + 1]; b[to + 1] = 0; }
        }
        if (Math.abs(pc) == K) kingSq[ki(s)] = from;
        castle = uCastle[sp];
        ep = uEp[sp];
        half = uHalf[sp];
        side = s;
    }

    private void refreshLegal() {
        int n = gen(side, moveBuf[0], false);
        nLegal = 0;
        int s = side;
        for (int i = 0; i < n; i++) {
            make(moveBuf[0][i]);
            if (!inCheck(s)) legal[nLegal++] = moveBuf[0][i];
            unmake();
        }
    }

    // ------------------------------------------------------------ AI

    private int evaluate() {
        int sc = 0, minor = 0, other = 0;
        for (int sq = 0; sq < 64; sq++) {
            int v = b[sq];
            if (v == 0) continue;
            int t = Math.abs(v), s = v > 0 ? 1 : -1;
            int idx = s > 0 ? sq : (sq ^ 56);
            int ps = VALUE[t];
            if (t == P) ps += PST_P[idx];
            else if (t == N) { ps += PST_N[idx]; minor++; }
            else if (t == B) { ps += PST_N[idx] / 2 + 10; minor++; }
            else if (t == K) ps += PST_K[idx];
            else other++;
            sc += s * ps;
        }
        return sc;
    }

    private boolean insufficient() {
        int minors = 0;
        for (int sq = 0; sq < 64; sq++) {
            int t = Math.abs(b[sq]);
            if (t == P || t == R || t == Q) return false;
            if (t == N || t == B) minors++;
        }
        return minors <= 1;
    }

    private int quiesce(int alpha, int beta, int ply, int qd) {
        int stand = evaluate() * side;
        if (stand >= beta) return beta;
        if (stand > alpha) alpha = stand;
        if (qd == 0 || ply >= 7) return alpha;
        int[] ms = moveBuf[ply];
        int n = gen(side, ms, true);
        order(ms, n);
        int s = side;
        for (int i = 0; i < n; i++) {
            make(ms[i]);
            if (inCheck(s)) { unmake(); continue; }
            int v = -quiesce(-beta, -alpha, ply + 1, qd - 1);
            unmake();
            if (v >= beta) return beta;
            if (v > alpha) alpha = v;
        }
        return alpha;
    }

    private void order(int[] ms, int n) {
        // captures first (MVV-LVA), simple insertion sort
        for (int i = 0; i < n; i++) {
            int m = ms[i];
            int to = (m >> 6) & 63, from = m & 63;
            scoreBuf[i] = (b[to] != 0 ? Math.abs(b[to]) * 10 - Math.abs(b[from]) + 100 : 0) + ((m >> 12) == 8 ? 90 : 0);
        }
        for (int i = 1; i < n; i++) {
            int m = ms[i], s = scoreBuf[i], j = i - 1;
            while (j >= 0 && scoreBuf[j] < s) {
                ms[j + 1] = ms[j];
                scoreBuf[j + 1] = scoreBuf[j];
                j--;
            }
            ms[j + 1] = m;
            scoreBuf[j + 1] = s;
        }
    }

    private int search(int depth, int alpha, int beta, int ply) {
        if (depth == 0) return mode == 0 ? evaluate() * side : quiesce(alpha, beta, ply, 4);
        if (half >= 100) return 0;
        int[] ms = moveBuf[ply];
        int n = gen(side, ms, false);
        order(ms, n);
        int s = side, legalCount = 0;
        for (int i = 0; i < n; i++) {
            make(ms[i]);
            if (inCheck(s)) { unmake(); continue; }
            legalCount++;
            int v = -search(depth - 1, -beta, -alpha, ply + 1);
            unmake();
            if (v >= beta) return beta;
            if (v > alpha) alpha = v;
        }
        if (legalCount == 0) return inCheck(s) ? -MATE + ply : 0;
        return alpha;
    }

    private int bestMove() {
        int depth = mode == 0 ? 1 : (mode == 1 ? 2 : 3);
        int best = -MATE * 2, bestM = legal[0], ties = 0;
        int[] root = new int[nLegal];
        System.arraycopy(legal, 0, root, 0, nLegal);
        for (int i = 0; i < root.length; i++) {
            make(root[i]);
            int v = -search(depth - 1, -MATE * 2, MATE * 2, 1);
            unmake();
            if (mode == 0) v += Rnd.range(-40, 40);
            if (v > best) {
                best = v;
                bestM = root[i];
                ties = 1;
            } else if (v == best && Rnd.nextInt(++ties) == 0) {
                bestM = root[i];
            }
        }
        return bestM;
    }

    // ------------------------------------------------------------ game flow

    private void play(int m) {
        lastFrom = m & 63;
        lastTo = (m >> 6) & 63;
        boolean cap = b[lastTo] != 0 || ((m >> 12) & 1) != 0;
        make(m);
        sp = 0; // history is not needed for undo in play
        refreshLegal();
        if (cap) Sfx.tone(64, 40); else Sfx.click();
        if (nLegal == 0) {
            if (inCheck(side)) {
                boolean playerWon = side < 0;
                headline = playerWon ? "CHECKMATE!" : "CHECKMATED";
                saved[playerWon ? 0 : 1]++;
                endGame(playerWon);
            } else {
                headline = "STALEMATE";
                saved[2]++;
                endGame(false);
            }
        } else if (half >= 100 || insufficient()) {
            headline = "DRAW";
            saved[2]++;
            endGame(false);
        } else if (inCheck(side)) {
            Sfx.tone(84, 60);
        }
    }

    protected void update() {
        if (side < 0) {
            if (!thinking) {
                thinking = true;
                thinkDelay = 2;
                return;
            }
            if (--thinkDelay > 0) return;
            int m = bestMove();
            thinking = false;
            play(m);
            return;
        }
        int dx = 0, dy = 0;
        if ((pressed & K_LEFT) != 0) dx = -1;
        if ((pressed & K_RIGHT) != 0) dx = 1;
        if ((pressed & K_UP) != 0) dy = -1;
        if ((pressed & K_DOWN) != 0) dy = 1;
        if (digit(1)) { dx = -1; dy = -1; }
        if (digit(3)) { dx = 1; dy = -1; }
        if (digit(7)) { dx = -1; dy = 1; }
        if (digit(9)) { dx = 1; dy = 1; }
        curX = (curX + dx + 8) & 7;
        curY = (curY + dy + 8) & 7;
        int sq = curY * 8 + curX;
        if (digit(0)) sel = -1;
        if ((pressed & K_FIRE) != 0) {
            if (sel >= 0) {
                for (int i = 0; i < nLegal; i++) {
                    if ((legal[i] & 63) == sel && ((legal[i] >> 6) & 63) == sq) {
                        sel = -1;
                        play(legal[i]);
                        return;
                    }
                }
            }
            if (b[sq] > 0) {
                boolean any = false;
                for (int i = 0; i < nLegal; i++) if ((legal[i] & 63) == sq) any = true;
                sel = any ? sq : -1;
                if (!any) Sfx.bad(); else Sfx.click();
            } else {
                sel = -1;
            }
        }
    }

    // ------------------------------------------------------------ drawing

    private static final String[][] ART = {
        null,
        { "..........", "....11....", "...1221...", "...1221...", "....11....", "...1221...", "...1221...", "..122221..", ".12222221.", ".11111111." },
        { "...1111...", "..122221..", ".12122221.", ".12222221.", "..1112221.", "....12221.", "...122221.", "..1222221.", ".12222221.", ".11111111." },
        { "....11....", "...1221...", "..122121..", "..121221..", "..122221..", "...1221...", "....11....", "...1221...", "..122221..", ".11111111." },
        { ".11.11.11.", ".11111111.", "..122221..", "..122221..", "..122221..", "..122221..", "..122221..", ".12222221.", ".12222221.", ".11111111." },
        { ".1..11..1.", ".1.1221.1.", ".12122121.", ".12222221.", "..122221..", "..122221..", "...1221...", "..122221..", ".12222221.", ".11111111." },
        { "....11....", "...1221...", "....11....", "..111111..", ".12222221.", ".12222221.", "..122221..", "..122221..", ".12222221.", ".11111111." },
    };

    private void buildSprites(int scale) {
        sprites = new Image[14];
        int[] white = { 0, 0x1A1A1A, 0xF6F1E3 };
        int[] black = { 0, 0xE8E0D0, 0x2B2B33 };
        for (int t = 1; t <= 6; t++) {
            sprites[t] = Gfx.sprite(ART[t], white, scale);
            sprites[t + 7] = Gfx.sprite(ART[t], black, scale);
        }
        spriteScale = scale;
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        int cell = Math.max(8, Math.min(W, H - hud * 2) / 8);
        int ox = (W - cell * 8) / 2, oy = hud + (H - hud * 2 - cell * 8) / 2;
        int scale = Math.max(1, (cell - 2) / 10);
        if (sprites == null || spriteScale != scale) buildSprites(scale);
        g.setColor(0x1C140C);
        g.fillRect(0, 0, W, H);
        int checkSq = (state == PLAY || state == OVER) && inCheck(side) ? kingSq[ki(side)] : -1;
        for (int sq = 0; sq < 64; sq++) {
            int x = ox + (sq & 7) * cell, y = oy + (sq >> 3) * cell;
            boolean light = ((sq >> 3) + (sq & 7)) % 2 == 0;
            int c = light ? 0xE9D3A6 : 0xA7774A;
            if (sq == lastFrom || sq == lastTo) c = light ? 0xE6DA7A : 0xB9A445;
            if (sq == sel) c = 0x7CC46A;
            if (sq == checkSq) c = 0xE0503C;
            g.setColor(c);
            g.fillRect(x, y, cell, cell);
            int v = b[sq];
            if (v != 0) {
                Image im = sprites[v > 0 ? v : -v + 7];
                g.drawImage(im, x + cell / 2, y + cell / 2, Graphics.HCENTER | Graphics.VCENTER);
            }
        }
        if (sel >= 0) {
            g.setColor(0x2E7D32);
            for (int i = 0; i < nLegal; i++) {
                if ((legal[i] & 63) != sel) continue;
                int to = (legal[i] >> 6) & 63;
                int x = ox + (to & 7) * cell, y = oy + (to >> 3) * cell;
                if (b[to] != 0) g.drawRect(x + 1, y + 1, cell - 3, cell - 3);
                else Gfx.disc(g, x + cell / 2, y + cell / 2, Math.max(2, cell / 6));
            }
        }
        if (state == PLAY && side > 0) {
            g.setColor((clock & 4) == 0 ? 0x1E88E5 : 0x64B5F6);
            int x = ox + curX * cell, y = oy + curY * cell;
            g.drawRect(x, y, cell - 1, cell - 1);
            g.drawRect(x + 1, y + 1, cell - 3, cell - 3);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xF0E0C0);
        String status = thinking ? "Thinking..." : (side > 0 ? "Your move" : "Black to move");
        if (state == PLAY && checkSq >= 0 && !thinking) status = "Check!";
        g.drawString(status, W / 2, 1, Gfx.TC);
        g.setFont(Gfx.SMALL);
        g.setColor(0xB89E78);
        g.drawString(modes()[mode] + " AI", W / 2, H - hud + 1, Gfx.TC);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int cell = Math.max(10, Math.min(h, w / 6));
        int scale = Math.max(1, (cell - 2) / 10);
        if (sprites == null || spriteScale != scale) buildSprites(scale);
        int order[] = { R, N, B, Q, K, B };
        int n = Math.min(6, w / cell);
        int x0 = x + (w - n * cell) / 2, y0 = y + (h - cell) / 2;
        for (int i = 0; i < n; i++) {
            g.setColor(i % 2 == 0 ? 0xE9D3A6 : 0xA7774A);
            g.fillRect(x0 + i * cell, y0, cell, cell);
            int bob = ((clock / 3 + i) % 6 == 0) ? -2 : 0;
            g.drawImage(sprites[order[i] + (i % 2 == 0 ? 0 : 7)], x0 + i * cell + cell / 2, y0 + cell / 2 + bob, Graphics.HCENTER | Graphics.VCENTER);
        }
    }
}
