package molebonk;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Mole Bonk: the 3x3 field IS your keypad. Bonk moles with keys 1-9, spare the bunnies. */
public class MoleGame extends Game {
    private static final int MOLE = 1, GOLD = 2, BUNNY = 3;
    private final int[] kind = new int[9], life = new int[9], maxLife = new int[9], hitT = new int[9];
    private int combo, misses, timeLeft, spawnT, cursor = 4, flash;
    private Image moleImg, goldImg, bunnyImg, hitImg;
    private int imgScale;

    protected String name() { return "Mole Bonk"; }

    protected String[] help() {
        return new String[] {
            "Moles pop out of nine holes laid out exactly like your keypad. Press the matching number key (1-9) to bonk them before they duck back down.",
            "Golden moles are worth five times as much. Never bonk a bunny! Consecutive hits build a combo.",
            "Classic lasts 60 seconds. In Endless, three escaped moles end the game.",
            "- Controls",
            "1-9: bonk that hole",
            "Joystick + select also works",
        };
    }

    protected String[] modes() { return new String[] { "Classic 60s", "Endless" }; }

    protected int accent() { return 0x8D6E63; }

    protected void newGame() {
        for (int i = 0; i < 9; i++) {
            kind[i] = 0;
            hitT[i] = 0;
        }
        combo = 0;
        misses = 0;
        timeLeft = 60 * 20;
        spawnT = 10;
    }

    private void sprites(int sc) {
        if (moleImg != null && imgScale == sc) return;
        imgScale = sc;
        String[] mole = { "..1111..", ".122221.", "12322321", "12222221", "12244221", ".125521.", ".122221.", "12222221" };
        String[] bunny = { ".11..11.", ".16..61.", ".16..61.", ".166661.", "16366361", "16666661", ".164461.", ".166661." };
        String[] dizzy = { "..1111..", ".122221.", "12722721", "12222221", "12266221", ".122221.", ".122221.", "12222221" };
        moleImg = Gfx.sprite(mole, new int[] { 0, 0x3E2723, 0x795548, 0x000000, 0xFFAB91, 0xFFFFFF, 0, 0 }, sc);
        goldImg = Gfx.sprite(mole, new int[] { 0, 0x5D4037, 0xFFC107, 0x000000, 0xFFAB91, 0xFFFFFF, 0, 0 }, sc);
        bunnyImg = Gfx.sprite(bunny, new int[] { 0, 0x616161, 0, 0x000000, 0xF48FB1, 0, 0xFAFAFA, 0 }, sc);
        hitImg = Gfx.sprite(dizzy, new int[] { 0, 0x3E2723, 0x795548, 0, 0, 0, 0xFFAB91, 0xFFEB3B }, sc);
    }

    protected void update() {
        if (flash > 0) flash--;
        int elapsed = frame;
        if (mode == 0) {
            timeLeft--;
            if (timeLeft <= 0) {
                headline = "TIME UP";
                endGame(true);
                return;
            }
        }
        int speedup = Math.min(20, elapsed / 60);
        if (--spawnT <= 0) {
            int tries = 0;
            int h;
            do {
                h = Rnd.nextInt(9);
            } while (kind[h] != 0 && ++tries < 12);
            if (kind[h] == 0 && hitT[h] == 0) {
                int r = Rnd.nextInt(100);
                kind[h] = r < 8 ? GOLD : (r < 22 ? BUNNY : MOLE);
                maxLife[h] = life[h] = Math.max(14, 40 - speedup - (kind[h] == GOLD ? 8 : 0));
            }
            spawnT = Math.max(4, 16 - speedup / 2) + Rnd.nextInt(6);
        }
        for (int i = 0; i < 9; i++) {
            if (hitT[i] > 0 && --hitT[i] == 0) kind[i] = 0;
            if (kind[i] == 0 || hitT[i] > 0) continue;
            if (--life[i] <= 0) {
                if (kind[i] != BUNNY) {
                    combo = 0;
                    if (mode == 1) {
                        misses++;
                        Sfx.tone(45, 40);
                        if (misses >= 3) {
                            headline = "THEY GOT AWAY";
                            kind[i] = 0;
                            endGame(false);
                            return;
                        }
                    }
                }
                kind[i] = 0;
            }
        }
        int d = digitPressed();
        int hole = -1;
        if (d >= 1) hole = d - 1;
        else {
            int x = cursor % 3, y = cursor / 3;
            if ((pressed & K_LEFT) != 0 && x > 0) x--;
            if ((pressed & K_RIGHT) != 0 && x < 2) x++;
            if ((pressed & K_UP) != 0 && y > 0) y--;
            if ((pressed & K_DOWN) != 0 && y < 2) y++;
            cursor = y * 3 + x;
            if ((pressed & K_FIRE) != 0) hole = cursor;
        }
        if (hole >= 0) bonk(hole);
    }

    private void bonk(int h) {
        cursor = h;
        if (kind[h] == 0 || hitT[h] > 0) {
            combo = 0;
            Sfx.tone(40, 15);
            return;
        }
        if (kind[h] == BUNNY) {
            score = Math.max(0, score - 30);
            combo = 0;
            flash = 8;
            kind[h] = 0;
            Sfx.bad();
            return;
        }
        combo++;
        int pts = (kind[h] == GOLD ? 50 : 10) * (1 + combo / 5);
        score += pts;
        hitT[h] = 8;
        Sfx.tone(kind[h] == GOLD ? 90 : 76 + Math.min(combo, 12), 30);
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        g.setColor(flash > 0 && (flash & 2) != 0 ? 0xB71C1C : 0x7CB342);
        g.fillRect(0, 0, W, H);
        g.setColor(0x689F38);
        for (int y = hud; y < H; y += 6) for (int x = (y / 6 & 1) * 3; x < W; x += 6) g.fillRect(x, y, 2, 1);
        int size = Math.min(W - 6, H - hud * 2);
        int c = size / 3;
        int ox = (W - c * 3) / 2, oy = hud + (H - hud - c * 3) / 2;
        int sc = Math.max(1, c / 14);
        sprites(sc);
        for (int i = 0; i < 9; i++) {
            int x = ox + (i % 3) * c, y = oy + (i / 3) * c;
            int hx = x + c / 2, hy = y + c * 2 / 3;
            g.setColor(0x4E342E);
            g.fillArc(hx - c * 2 / 5, hy - c / 8, c * 4 / 5, c / 4, 0, 360);
            if (kind[i] != 0) {
                Image im = hitT[i] > 0 ? hitImg : (kind[i] == GOLD ? goldImg : (kind[i] == BUNNY ? bunnyImg : moleImg));
                int rise = hitT[i] > 0 ? hitT[i] * im.getHeight() / 10 : Math.min(im.getHeight(), (maxLife[i] - life[i]) * 3 * sc + 2);
                if (life[i] < 5) rise = Math.min(rise, life[i] * 3 * sc);
                g.setClip(x, y, c, hy - y);
                g.drawImage(im, hx, hy - rise + im.getHeight(), Graphics.HCENTER | Graphics.BOTTOM);
                g.setClip(0, 0, W, H);
            }
            g.setColor(0x5D4037);
            g.fillArc(hx - c * 2 / 5, hy - 2, c * 4 / 5, c / 8 + 2, 180, 180);
            g.setFont(Gfx.SMALL_B);
            g.setColor(i == cursor ? 0xFFEB3B : 0xDCEDC8);
            g.drawString(String.valueOf(i + 1), x + 3, y + 2, Gfx.TL);
        }
        g.setColor(0x33691E);
        g.fillRect(0, 0, W, hud);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString(String.valueOf(score), 2, 1, Gfx.TL);
        g.setColor(0xFFEB3B);
        if (combo >= 5) g.drawString("x" + (1 + combo / 5), W / 2, 1, Gfx.TC);
        g.setColor(0xFFFFFF);
        g.drawString(mode == 0 ? (timeLeft / 20 + "s") : ("miss " + misses + "/3"), W - 2, 1, Gfx.TR);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        int sc = Math.max(1, Math.min(h / 12, w / 30));
        sprites(sc);
        for (int k = 0; k < 3; k++) {
            int hx = x + (k + 1) * w / 4, hy = y + h - 4;
            g.setColor(0x4E342E);
            g.fillArc(hx - 10, hy - 3, 20, 6, 0, 360);
            boolean up = ((clock / 8 + k * 2) % 6) < 3;
            if (up) {
                g.setClip(x, y, w, hy - y);
                g.drawImage(k == 1 ? goldImg : moleImg, hx, hy + 2, Graphics.HCENTER | Graphics.BOTTOM);
                g.setClip(0, 0, W, H);
            }
        }
    }
}
