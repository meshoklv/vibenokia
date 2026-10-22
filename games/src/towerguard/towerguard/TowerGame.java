package towerguard;

import gamekit.FMath;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Tower Guard: build and upgrade towers along a path to stop 20 waves. */
public class TowerGame extends Game {
    private static final int GC = 10, GR = 12, WAVES = 20, MAXM = 40, MAXS = 16;
    private static final String[] PATHS = {
        "0,1 7,1 7,4 2,4 2,7 8,7 8,10 1,10 1,11",
        "4,0 4,3 1,3 1,6 6,6 6,2 8,2 8,9 3,9 3,11",
        "0,0 9,0 9,11 0,11 0,2 7,2 7,9 2,9 2,4 5,4 5,7",
    };
    private static final String[] TNAME = { "Arrow", "Cannon", "Frost" };
    private static final int[] TCOST = { 10, 25, 20 };
    private static final int[] TDMG = { 4, 11, 1 };
    private static final int[] TRANGE = { 160, 130, 135 }; // cells * 64
    private static final int[] TRATE = { 7, 20, 12 };
    private static final int[] TCOLOR = { 0x8BC34A, 0xFF7043, 0x4FC3F7 };

    private final int[] pathX = new int[GC * GR], pathY = new int[GC * GR];
    private int pathLen;
    private final boolean[] road = new boolean[GC * GR];
    private final int[] tower = new int[GC * GR];   // 0 none, else type+1
    private final int[] tlevel = new int[GC * GR];
    private final int[] tcool = new int[GC * GR];
    // monsters
    private final int[] mp = new int[MAXM], mhp = new int[MAXM], mmax = new int[MAXM], mspd = new int[MAXM];
    private final int[] mslow = new int[MAXM], mtype = new int[MAXM];
    private final boolean[] malive = new boolean[MAXM];
    // shot effects
    private final int[] sx0 = new int[MAXS], sy0 = new int[MAXS], sx1 = new int[MAXS], sy1 = new int[MAXS], st = new int[MAXS], sc = new int[MAXS];
    private int lives, gold, wave, toSpawn, spawnTimer, countdown, kills;
    private int cur = GC * 5 + 5;
    private int menu, menuSel; // 0 none, 1 build, 2 tower
    private int cell, ox, oy, hud;

    protected String name() { return "Tower Guard"; }

    protected String[] help() {
        return new String[] {
            "Monsters march along the dirt road. Build towers beside it to stop them; every monster that gets through costs a life (big ones cost two).",
            "Arrow towers fire fast, Cannons hit everything near the target, Frost towers slow monsters down. Press 5 on a tower to upgrade (3 levels) or sell it.",
            "Survive all 20 waves. Press # to call the next wave early for bonus gold.",
            "- Controls",
            "2/4/6/8: move cursor",
            "5: build / tower menu",
            "4/6 + 5 in menus, 0: close menu",
            "#: next wave now",
        };
    }

    protected String[] modes() { return new String[] { "Meadow", "Canyon", "Spiral" }; }

    protected int accent() { return 0x8BC34A; }

    protected void newGame() {
        for (int i = 0; i < GC * GR; i++) {
            road[i] = false;
            tower[i] = 0;
            tlevel[i] = 0;
        }
        buildPath(PATHS[mode]);
        for (int i = 0; i < MAXM; i++) malive[i] = false;
        for (int i = 0; i < MAXS; i++) st[i] = 0;
        lives = 20;
        gold = 70;
        wave = 0;
        kills = 0;
        toSpawn = 0;
        countdown = 200;
        menu = 0;
        cur = GC * 5 + 4;
        if (road[cur]) cur = GC * 6 + 5;
    }

    private void buildPath(String spec) {
        pathLen = 0;
        int px = -1, py = -1;
        int i = 0;
        while (i < spec.length()) {
            int sp = spec.indexOf(' ', i);
            if (sp < 0) sp = spec.length();
            String pt = spec.substring(i, sp);
            int comma = pt.indexOf(',');
            int x = Integer.parseInt(pt.substring(0, comma)), y = Integer.parseInt(pt.substring(comma + 1));
            if (px < 0) {
                add(x, y);
            } else {
                while (px != x || py != y) {
                    px += FMath.sign(x - px);
                    py += FMath.sign(y - py);
                    add(px, py);
                }
            }
            px = x;
            py = y;
            i = sp + 1;
        }
    }

    private void add(int x, int y) {
        pathX[pathLen] = x;
        pathY[pathLen] = y;
        pathLen++;
        road[y * GC + x] = true;
    }

    private void layout() {
        hud = Gfx.SMALL.getHeight() + 2;
        cell = Math.max(6, Math.min(W / GC, (H - hud * 2) / GR));
        ox = (W - cell * GC) / 2;
        oy = hud + (H - hud * 2 - cell * GR) / 2;
    }

    /** Pixel position of progress p (<<8 cells) along the path. */
    private int posX(int p) {
        int i = p >> 8, f = p & 255;
        if (i >= pathLen - 1) return ox + pathX[pathLen - 1] * cell + cell / 2;
        int a = pathX[i] * cell, b = pathX[i + 1] * cell;
        return ox + a + (b - a) * f / 256 + cell / 2;
    }

    private int posY(int p) {
        int i = p >> 8, f = p & 255;
        if (i >= pathLen - 1) return oy + pathY[pathLen - 1] * cell + cell / 2;
        int a = pathY[i] * cell, b = pathY[i + 1] * cell;
        return oy + a + (b - a) * f / 256 + cell / 2;
    }

    // ------------------------------------------------------------ update

    protected void update() {
        layout();
        handleInput();
        if (toSpawn == 0 && !anyAlive()) {
            if (wave >= WAVES) {
                score = kills * 10 + lives * 50 + gold;
                endGame(true);
                return;
            }
            if (--countdown <= 0 || (pressed & K_POUND) != 0) {
                if (countdown > 0) gold += countdown / 20;
                startWave();
            }
        } else if ((pressed & K_POUND) != 0 && toSpawn == 0 && wave < WAVES) {
            gold += 5;
            startWave();
        }
        if (toSpawn > 0 && --spawnTimer <= 0) spawnMonster();
        moveMonsters();
        if (state != PLAY) return;
        fireTowers();
        for (int i = 0; i < MAXS; i++) if (st[i] > 0) st[i]--;
        score = kills * 10 + lives * 50;
    }

    private boolean anyAlive() {
        for (int i = 0; i < MAXM; i++) if (malive[i]) return true;
        return false;
    }

    private void startWave() {
        wave++;
        toSpawn = 6 + wave;
        spawnTimer = 0;
        countdown = 160;
        Sfx.tone(67, 60);
    }

    private void spawnMonster() {
        for (int i = 0; i < MAXM; i++) {
            if (malive[i]) continue;
            int t = wave % 5 == 0 && toSpawn <= 2 ? 2 : (wave > 3 && Rnd.chance(25) ? 1 : 0);
            int base = t == 2 ? 60 : (t == 1 ? 10 : 18);
            int hp = base + base * wave * wave / 18 + base * wave / 3;
            malive[i] = true;
            mtype[i] = t;
            mhp[i] = mmax[i] = hp;
            mspd[i] = t == 1 ? 26 : (t == 2 ? 9 : 15);
            mp[i] = 0;
            mslow[i] = 0;
            toSpawn--;
            spawnTimer = t == 1 ? 8 : 14;
            return;
        }
        spawnTimer = 5;
    }

    private void moveMonsters() {
        for (int i = 0; i < MAXM; i++) {
            if (!malive[i]) continue;
            int sp = mspd[i];
            if (mslow[i] > 0) {
                mslow[i]--;
                sp = sp / 2;
            }
            mp[i] += sp;
            if ((mp[i] >> 8) >= pathLen - 1) {
                malive[i] = false;
                lives -= mtype[i] == 2 ? 2 : 1;
                Sfx.bad();
                if (lives <= 0) {
                    lives = 0;
                    score = kills * 10;
                    endGame(false);
                    return;
                }
            }
        }
    }

    private void fireTowers() {
        for (int t = 0; t < GC * GR; t++) {
            if (tower[t] == 0) continue;
            if (tcool[t] > 0) { tcool[t]--; continue; }
            int type = tower[t] - 1, lv = tlevel[t];
            int tx = ox + (t % GC) * cell + cell / 2, ty = oy + (t / GC) * cell + cell / 2;
            int range = (TRANGE[type] + lv * 24) * cell / 64;
            int best = -1;
            for (int i = 0; i < MAXM; i++) {
                if (!malive[i]) continue;
                int dx = posX(mp[i]) - tx, dy = posY(mp[i]) - ty;
                if (dx * dx + dy * dy <= range * range && (best < 0 || mp[i] > mp[best])) best = i;
            }
            if (best < 0) continue;
            int dmg = TDMG[type] * (10 + lv * 8) / 10;
            int bx = posX(mp[best]), by = posY(mp[best]);
            if (type == 1) {
                int splash = cell * 6 / 5;
                for (int i = 0; i < MAXM; i++) {
                    if (!malive[i]) continue;
                    int dx = posX(mp[i]) - bx, dy = posY(mp[i]) - by;
                    if (dx * dx + dy * dy <= splash * splash) hurt(i, dmg);
                }
            } else {
                hurt(best, dmg);
                if (type == 2 && malive[best]) mslow[best] = 30 + lv * 10;
            }
            tcool[t] = TRATE[type] - lv * 2;
            shot(tx, ty, bx, by, TCOLOR[type]);
        }
    }

    private void hurt(int i, int dmg) {
        mhp[i] -= dmg;
        if (mhp[i] <= 0) {
            malive[i] = false;
            kills++;
            gold += mtype[i] == 2 ? 12 : (mtype[i] == 1 ? 3 : 2) + wave / 6;
            Sfx.tone(76, 15);
        }
    }

    private void shot(int x0, int y0, int x1, int y1, int c) {
        for (int i = 0; i < MAXS; i++) {
            if (st[i] == 0) {
                sx0[i] = x0;
                sy0[i] = y0;
                sx1[i] = x1;
                sy1[i] = y1;
                sc[i] = c;
                st[i] = 3;
                return;
            }
        }
    }

    private int upgradeCost(int t) {
        return TCOST[tower[t] - 1] * (tlevel[t] + 1);
    }

    private int sellValue(int t) {
        int total = 0;
        for (int l = 0; l <= tlevel[t]; l++) total += TCOST[tower[t] - 1] * (l == 0 ? 1 : l);
        return total * 6 / 10;
    }

    private void handleInput() {
        if (menu != 0) {
            int n = menu == 1 ? 3 : 2;
            if ((pressed & K_LEFT) != 0) menuSel = (menuSel + n - 1) % n;
            if ((pressed & K_RIGHT) != 0) menuSel = (menuSel + 1) % n;
            if ((pressed & (K_NUM0 | K_SOFT)) != 0) menu = 0;
            if ((pressed & K_FIRE) != 0) {
                if (menu == 1) {
                    if (gold >= TCOST[menuSel]) {
                        gold -= TCOST[menuSel];
                        tower[cur] = menuSel + 1;
                        tlevel[cur] = 0;
                        tcool[cur] = 0;
                        Sfx.good();
                        menu = 0;
                    } else {
                        Sfx.bad();
                    }
                } else if (menuSel == 0) {
                    if (tlevel[cur] < 2 && gold >= upgradeCost(cur)) {
                        gold -= upgradeCost(cur);
                        tlevel[cur]++;
                        Sfx.good();
                        menu = 0;
                    } else {
                        Sfx.bad();
                    }
                } else {
                    gold += sellValue(cur);
                    tower[cur] = 0;
                    Sfx.click();
                    menu = 0;
                }
            }
            return;
        }
        int x = cur % GC, y = cur / GC;
        if ((pressed & K_LEFT) != 0) x = (x + GC - 1) % GC;
        if ((pressed & K_RIGHT) != 0) x = (x + 1) % GC;
        if ((pressed & K_UP) != 0) y = (y + GR - 1) % GR;
        if ((pressed & K_DOWN) != 0) y = (y + 1) % GR;
        cur = y * GC + x;
        if ((pressed & K_FIRE) != 0) {
            if (road[cur]) {
                Sfx.bad();
            } else {
                menu = tower[cur] == 0 ? 1 : 2;
                menuSel = 0;
            }
        }
    }

    // ------------------------------------------------------------- draw

    protected void draw(Graphics g) {
        layout();
        g.setColor(0x1B2B16);
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < GC * GR; i++) {
            int x = ox + (i % GC) * cell, y = oy + (i / GC) * cell;
            if (road[i]) {
                g.setColor(0xB89A6A);
                g.fillRect(x, y, cell, cell);
            } else {
                g.setColor(((i % GC) + (i / GC)) % 2 == 0 ? 0x4C8C3A : 0x468434);
                g.fillRect(x, y, cell, cell);
            }
        }
        // entry / exit markers
        g.setColor(0x2E7D32);
        g.fillRect(ox + pathX[0] * cell + cell / 3, oy + pathY[0] * cell + cell / 3, cell / 3, cell / 3);
        g.setColor(0xC62828);
        g.fillRect(ox + pathX[pathLen - 1] * cell + cell / 3, oy + pathY[pathLen - 1] * cell + cell / 3, cell / 3, cell / 3);
        for (int t = 0; t < GC * GR; t++) {
            if (tower[t] == 0) continue;
            int x = ox + (t % GC) * cell, y = oy + (t / GC) * cell;
            int type = tower[t] - 1;
            Gfx.bevel(g, x + 1, y + 1, cell - 2, cell - 2, 0x6D6D6D);
            g.setColor(TCOLOR[type]);
            Gfx.disc(g, x + cell / 2, y + cell / 2, Math.max(2, cell / 3 - 1));
            g.setColor(0xFFFFFF);
            for (int l = 0; l <= tlevel[t]; l++) g.fillRect(x + 2 + l * 3, y + cell - 4, 2, 2);
        }
        for (int i = 0; i < MAXM; i++) {
            if (!malive[i]) continue;
            int x = posX(mp[i]), y = posY(mp[i]);
            int r = mtype[i] == 2 ? cell * 4 / 10 : (mtype[i] == 1 ? cell / 4 : cell / 3);
            g.setColor(mslow[i] > 0 ? 0x81D4FA : (mtype[i] == 2 ? 0x6A1B9A : (mtype[i] == 1 ? 0xFFEB3B : 0xE53935)));
            Gfx.disc(g, x, y, Math.max(2, r));
            g.setColor(0x000000);
            g.fillRect(x - cell / 3, y - r - 3, cell * 2 / 3, 2);
            g.setColor(0x76FF03);
            g.fillRect(x - cell / 3, y - r - 3, Math.max(1, cell * 2 / 3 * mhp[i] / mmax[i]), 2);
        }
        for (int i = 0; i < MAXS; i++) {
            if (st[i] == 0) continue;
            g.setColor(sc[i]);
            g.drawLine(sx0[i], sy0[i], sx1[i], sy1[i]);
            if (sc[i] == TCOLOR[1]) Gfx.ring(g, sx1[i], sy1[i], cell * (4 - st[i]) / 3);
        }
        int cx = ox + (cur % GC) * cell, cy = oy + (cur / GC) * cell;
        if (tower[cur] != 0) {
            int type = tower[cur] - 1;
            int range = (TRANGE[type] + tlevel[cur] * 24) * cell / 64;
            g.setColor(0xFFFFFF);
            Gfx.ring(g, cx + cell / 2, cy + cell / 2, range);
        }
        g.setColor((clock & 4) == 0 ? 0xFFFFFF : 0xFFEB3B);
        g.drawRect(cx, cy, cell - 1, cell - 1);
        // HUD
        g.setColor(0x0E160B);
        g.fillRect(0, 0, W, hud);
        g.fillRect(0, H - hud, W, hud);
        Font f = Gfx.SMALL_B;
        g.setFont(f);
        g.setColor(0xFF8A80);
        g.drawString("+" + lives, 2, 1, Gfx.TL);
        g.setColor(0xFFD54F);
        g.drawString("$" + gold, W / 2, 1, Gfx.TC);
        g.setColor(0xFFFFFF);
        g.drawString("W" + wave + "/" + WAVES, W - 2, 1, Gfx.TR);
        g.setFont(Gfx.SMALL);
        String hint;
        if (menu == 1) {
            hint = "< " + TNAME[menuSel] + " $" + TCOST[menuSel] + " >";
        } else if (menu == 2) {
            hint = menuSel == 0 ? (tlevel[cur] < 2 ? "< Upgrade $" + upgradeCost(cur) + " >" : "< Max level >")
                    : "< Sell +$" + sellValue(cur) + " >";
        } else if (toSpawn == 0 && !anyAlive()) {
            hint = "Wave " + (wave + 1) + " in " + (countdown / 20 + 1) + "s  #:go";
        } else {
            hint = "5:build/upgrade  #:next wave";
        }
        g.setColor(menu != 0 ? 0xFFEB3B : 0xC5E1A5);
        g.drawString(hint, W / 2, H - hud + 1, Gfx.TC);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int c = Math.max(8, Math.min(h / 3, w / 8));
        int n = w / c;
        int x0 = x + (w - n * c) / 2, y0 = y + (h - c * 3) / 2;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 3; j++) {
                g.setColor(j == 1 ? 0xB89A6A : ((i + j) % 2 == 0 ? 0x4C8C3A : 0x468434));
                g.fillRect(x0 + i * c, y0 + j * c, c, c);
            }
        }
        for (int i = 1; i < n; i += 3) {
            Gfx.bevel(g, x0 + i * c + 1, y0 + 1, c - 2, c - 2, 0x6D6D6D);
            g.setColor(TCOLOR[i % 3]);
            Gfx.disc(g, x0 + i * c + c / 2, y0 + c / 2, c / 3);
        }
        for (int k = 0; k < 3; k++) {
            int mx = x0 + ((clock * 2 + k * n * c / 3) % (n * c));
            g.setColor(0xE53935);
            Gfx.disc(g, mx, y0 + c + c / 2, c / 3);
        }
    }
}
