package seastrike;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/** Sea Strike: hidden-fleet naval battle against a hunting CPU admiral. */
public class SeaStrikeGame extends Game {
    private static final int N = 8;
    private static final int[] SHIPS = { 4, 3, 3, 2, 2 };
    private static final String[] SHIP_NAMES = { "Carrier", "Cruiser", "Frigate", "Patrol boat", "Sub" };
    private static final int PLACE = 0, AIM = 1, MY_SHOT = 2, CPU_TURN = 3, CPU_SHOT = 4;

    // ship ids are 1..5, 0 = water; shot: 0 none, 1 miss, 2 hit
    private final int[] mine = new int[N * N], theirs = new int[N * N];
    private final int[] myShots = new int[N * N], cpuShots = new int[N * N];
    private final int[] myHp = new int[6], cpuHp = new int[6];
    private int phase, cur, timer, lastCpu = -1, shots;
    private String msg = "";
    // CPU targeting memory
    private final int[] targets = new int[64];
    private int nTargets;

    protected String name() { return "Sea Strike"; }

    protected String[] help() {
        return new String[] {
            "Both admirals hide five ships on an 8x8 sea. Take turns firing one shot at a square of the enemy's sea. Hits burn orange, misses leave a white splash.",
            "Sink every enemy ship before the CPU sinks yours. Ships: Carrier (4), Cruiser (3), Frigate (3), Patrol boat (2) and Sub (2).",
            "At the start, press # to shuffle your fleet and 5 to set sail.",
            "- Controls",
            "2/4/6/8: aim  5: fire",
            "#: reshuffle fleet (before start)",
        };
    }

    protected String[] modes() { return new String[] { "Cadet", "Captain", "Admiral" }; }

    protected int accent() { return 0x2A9D8F; }

    protected void newGame() {
        place(mine, myHp);
        place(theirs, cpuHp);
        for (int i = 0; i < N * N; i++) {
            myShots[i] = 0;
            cpuShots[i] = 0;
        }
        nTargets = 0;
        shots = 0;
        phase = PLACE;
        cur = N * N / 2 - N / 2;
        lastCpu = -1;
        msg = "# shuffle  5 set sail";
    }

    private void place(int[] sea, int[] hp) {
        for (int i = 0; i < N * N; i++) sea[i] = 0;
        for (int s = 0; s < SHIPS.length; s++) {
            int len = SHIPS[s];
            hp[s + 1] = len;
            while (true) {
                boolean hor = Rnd.chance(50);
                int x = Rnd.nextInt(hor ? N - len + 1 : N), y = Rnd.nextInt(hor ? N : N - len + 1);
                boolean ok = true;
                for (int k = 0; k < len && ok; k++) {
                    int cx = x + (hor ? k : 0), cy = y + (hor ? 0 : k);
                    // keep a one-square gap between ships
                    for (int dy = -1; dy <= 1 && ok; dy++) {
                        for (int dx = -1; dx <= 1 && ok; dx++) {
                            int nx = cx + dx, ny = cy + dy;
                            if (nx >= 0 && ny >= 0 && nx < N && ny < N && sea[ny * N + nx] != 0) ok = false;
                        }
                    }
                }
                if (!ok) continue;
                for (int k = 0; k < len; k++) sea[(y + (hor ? 0 : k)) * N + x + (hor ? k : 0)] = s + 1;
                break;
            }
        }
    }

    private boolean allSunk(int[] hp) {
        for (int s = 1; s <= 5; s++) if (hp[s] > 0) return false;
        return true;
    }

    protected void update() {
        switch (phase) {
            case PLACE:
                if ((pressed & K_POUND) != 0) {
                    place(mine, myHp);
                    Sfx.click();
                }
                if ((pressed & K_FIRE) != 0) {
                    phase = AIM;
                    msg = "Your turn: fire!";
                }
                break;
            case AIM: {
                int x = cur % N, y = cur / N;
                if ((pressed & K_LEFT) != 0) x = (x + N - 1) % N;
                if ((pressed & K_RIGHT) != 0) x = (x + 1) % N;
                if ((pressed & K_UP) != 0) y = (y + N - 1) % N;
                if ((pressed & K_DOWN) != 0) y = (y + 1) % N;
                cur = y * N + x;
                if ((pressed & K_FIRE) != 0) {
                    if (myShots[cur] != 0) {
                        Sfx.bad();
                        break;
                    }
                    shots++;
                    int ship = theirs[cur];
                    myShots[cur] = ship != 0 ? 2 : 1;
                    if (ship != 0) {
                        cpuHp[ship]--;
                        msg = cpuHp[ship] == 0 ? "You sank their " + SHIP_NAMES[ship - 1] + "!" : "HIT!";
                        Sfx.good();
                    } else {
                        msg = "Miss.";
                        Sfx.tone(48, 40);
                    }
                    if (allSunk(cpuHp)) {
                        score = Math.max(100, 1200 - shots * 15) + mode * 300;
                        headline = "FLEET DESTROYED!";
                        endGame(true);
                        return;
                    }
                    phase = MY_SHOT;
                    timer = 18;
                }
                break;
            }
            case MY_SHOT:
                if (--timer <= 0) {
                    phase = CPU_TURN;
                    timer = 14;
                    msg = "Enemy is aiming...";
                }
                break;
            case CPU_TURN:
                if (--timer <= 0) cpuFire();
                break;
            default: // CPU_SHOT
                if (--timer <= 0 || (pressed & K_FIRE) != 0) {
                    phase = AIM;
                    msg = "Your turn: fire!";
                }
                break;
        }
    }

    private void cpuFire() {
        int t = pickTarget();
        lastCpu = t;
        int ship = mine[t];
        cpuShots[t] = ship != 0 ? 2 : 1;
        if (ship != 0) {
            myHp[ship]--;
            Sfx.bad();
            if (myHp[ship] == 0) {
                msg = "They sank your " + SHIP_NAMES[ship - 1] + "!";
                nTargets = 0;
            } else {
                msg = "Your ship is hit!";
                addNeighbours(t);
            }
            if (allSunk(myHp)) {
                score = 0;
                headline = "YOUR FLEET SANK";
                endGame(false);
                return;
            }
        } else {
            msg = "They missed.";
            Sfx.tone(60, 30);
        }
        phase = CPU_SHOT;
        timer = 22;
    }

    private void addNeighbours(int t) {
        int x = t % N, y = t / N;
        // if we already hit an adjacent square, prefer continuing the line
        int[] dx = { -1, 1, 0, 0 }, dy = { 0, 0, -1, 1 };
        for (int d = 0; d < 4; d++) {
            int nx = x + dx[d], ny = y + dy[d];
            if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
            int i = ny * N + nx;
            if (cpuShots[i] != 0) continue;
            boolean inLine = false;
            int bx = x - dx[d], by = y - dy[d];
            if (bx >= 0 && by >= 0 && bx < N && by < N && cpuShots[by * N + bx] == 2 && mine[by * N + bx] == mine[t]) inLine = true;
            if (nTargets < targets.length) {
                if (inLine && mode > 0) {
                    System.arraycopy(targets, 0, targets, 1, nTargets);
                    targets[0] = i;
                    nTargets++;
                } else {
                    targets[nTargets++] = i;
                }
            }
        }
    }

    private int pickTarget() {
        if (mode > 0) {
            while (nTargets > 0) {
                int i = targets[0];
                System.arraycopy(targets, 1, targets, 0, --nTargets);
                if (cpuShots[i] == 0) return i;
            }
        }
        // hunt: Admiral uses a checkerboard parity and avoids squares next to sunk ships
        for (int tries = 0; tries < 400; tries++) {
            int i = Rnd.nextInt(N * N);
            if (cpuShots[i] != 0) continue;
            if (mode == 2 && tries < 300 && ((i % N + i / N) & 1) == 1) continue;
            return i;
        }
        for (int i = 0; i < N * N; i++) if (cpuShots[i] == 0) return i;
        return 0;
    }

    private void drawSea(Graphics g, int ox, int oy, int c, int[] sea, int[] shotsMap, boolean showShips, int highlight) {
        for (int i = 0; i < N * N; i++) {
            int x = ox + (i % N) * c, y = oy + (i / N) * c;
            int wave = ((i % N) + (i / N) + clock / 6) % 4;
            g.setColor(wave == 0 ? 0x1E6091 : 0x1A5784);
            g.fillRect(x, y, c, c);
            g.setColor(0x15476D);
            g.drawRect(x, y, c - 1, c - 1);
            if (showShips && sea[i] != 0) {
                g.setColor(0x90A4AE);
                g.fillRect(x + 1, y + 1, c - 2, c - 2);
                g.setColor(0x607D8B);
                g.drawRect(x + 1, y + 1, c - 3, c - 3);
            }
            if (shotsMap[i] == 1) {
                g.setColor(0xE3F2FD);
                g.fillRect(x + c / 2 - 1, y + c / 2 - 1, 2, 2);
            } else if (shotsMap[i] == 2) {
                g.setColor(0xFF5722);
                Gfx.disc(g, x + c / 2, y + c / 2, c / 3);
                g.setColor(0xFFC107);
                Gfx.disc(g, x + c / 2, y + c / 2, Math.max(1, c / 6));
            }
            if (i == highlight) {
                g.setColor((clock & 4) == 0 ? 0xFFFFFF : 0xFFEB3B);
                g.drawRect(x, y, c - 1, c - 1);
                g.drawRect(x + 1, y + 1, c - 3, c - 3);
            }
        }
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        g.setColor(0x0B2239);
        g.fillRect(0, 0, W, H);
        int c = Math.max(8, Math.min((W - 6) / N, (H - hud * 3) / N));
        int ox = (W - c * N) / 2, oy = hud * 2 + (H - hud * 3 - c * N) / 2;
        boolean showMine = phase == PLACE || phase == CPU_TURN || phase == CPU_SHOT;
        if (showMine) drawSea(g, ox, oy, c, mine, cpuShots, true, phase == CPU_SHOT ? lastCpu : -1);
        else drawSea(g, ox, oy, c, theirs, myShots, state == OVER, phase == AIM ? cur : -1);
        g.setFont(Gfx.SMALL_B);
        g.setColor(showMine ? 0x90CAF9 : 0xFFAB91);
        g.drawString(showMine ? "YOUR FLEET" : "ENEMY WATERS", W / 2, 1, Gfx.TC);
        // ship tallies
        g.setFont(Gfx.SMALL);
        int left = 0, right = 0;
        for (int s = 1; s <= 5; s++) {
            if (myHp[s] > 0) left++;
            if (cpuHp[s] > 0) right++;
        }
        g.setColor(0x90CAF9);
        g.drawString("You " + left, 2, hud, Gfx.TL);
        g.setColor(0xFFAB91);
        g.drawString("Foe " + right, W - 2, hud, Gfx.TR);
        g.setColor(0xFFFFFF);
        g.drawString(msg, W / 2, H - hud + 1, Gfx.TC);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        g.setColor(0x1A5784);
        g.fillRect(x, y + h / 2, w, h / 2);
        g.setColor(0x1E6091);
        for (int k = 0; k < w; k += 8) g.fillRect(x + (k + clock) % w, y + h / 2 + 2 + (k % 3), 4, 1);
        int sx = x + w / 3;
        g.setColor(0x90A4AE);
        g.fillTriangle(sx - w / 6, y + h / 2, sx + w / 6, y + h / 2, sx + w / 8, y + h / 2 + h / 6);
        g.fillTriangle(sx - w / 6, y + h / 2, sx + w / 8, y + h / 2 + h / 6, sx - w / 8, y + h / 2 + h / 6);
        g.fillRect(sx - 4, y + h / 2 - h / 6, 8, h / 6);
        g.setColor(0x37474F);
        g.fillRect(sx + 2, y + h / 2 - h / 4, 3, h / 10);
        int t = clock % 30;
        if (t < 15) {
            g.setColor(0xFFC107);
            Gfx.disc(g, x + w * 3 / 4, y + h / 2, t / 2 + 1);
        }
    }
}
