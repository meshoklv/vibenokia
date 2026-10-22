package stardefender;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Star Defender: hold off a marching alien formation from behind crumbling shields. */
public class DefenderGame extends Game {
    private static final int ROWS = 5, MAXAB = 5, SH_W = 8, SH_H = 4;
    private static final String[][] ALIENS = {
        { "..1111..", ".111111.", "11.11.11", "11111111", ".1.11.1.", "1.1..1.1" },
        { "..1111..", ".111111.", "11.11.11", "11111111", ".1.11.1.", ".1....1." },
        { "1......1", ".111111.", "11.11.11", "11111111", "1.1111.1", "..1..1.." },
        { ".1....1.", ".111111.", "11.11.11", "11111111", "1.1111.1", ".1....1." },
        { "..1111..", ".111111.", "11122111", ".111111.", "..1..1..", ".1....1." },
        { "..1111..", ".111111.", "11122111", ".111111.", "..1..1..", "1......1" },
    };
    private static final int[] ROW_TYPE = { 2, 0, 0, 1, 1 };
    private static final int[] TYPE_COLOR = { 0x7CFC9A, 0x6FC3FF, 0xFF7AC8 };
    private static final int[] TYPE_PTS = { 30, 20, 10 };

    private int cols, aw, ah, gapX, gapY, fx, fy, dir, marchTimer, anim, alive;
    private boolean[] al;
    private final int[] abx = new int[MAXAB], aby = new int[MAXAB];
    private final boolean[] abOn = new boolean[MAXAB];
    private int px, shipY, bulX, bulY, lives, wave, deathTimer;
    private boolean bulOn;
    private boolean[][] shield;
    private int shields, shY, shCell;
    private int ufoX, ufoTimer, ufoPop, ufoPopX;
    private boolean ufoOn;
    private Image[][] alienImg;
    private Image ship;
    private int imgScale;

    protected String name() { return "Star Defender"; }

    protected String[] help() {
        return new String[] {
            "An alien formation marches back and forth, dropping lower each time it reaches the edge. Shoot them all before they land.",
            "Hide behind the shields, though shots from either side wear them away. Hit the mystery ship that crosses the top for a big bonus.",
            "The formation speeds up as it shrinks. Three lives.",
            "- Controls",
            "4/6: move  5 or 2: fire",
        };
    }

    protected int accent() { return 0x7CFC9A; }

    protected void newGame() {
        lives = 3;
        wave = 0;
        startWave();
    }

    private void layout() {
        cols = W >= 170 ? 8 : 6;
        int sc = Math.max(1, W / ((cols + 3) * 10));
        if (alienImg == null || imgScale != sc) {
            imgScale = sc;
            alienImg = new Image[3][2];
            for (int t = 0; t < 3; t++) {
                for (int f = 0; f < 2; f++) alienImg[t][f] = Gfx.sprite(ALIENS[t * 2 + f], new int[] { 0, TYPE_COLOR[t], 0xFFFFFF }, sc);
            }
            ship = Gfx.sprite(new String[] { "...11...", "...11...", ".111111.", "11111111", "11111111" }, new int[] { 0, 0x33DDFF }, sc);
        }
        aw = 8 * sc;
        ah = 6 * sc;
        gapX = aw + Math.max(3, aw / 2);
        gapY = ah + Math.max(3, ah / 2);
        shipY = H - 5 * sc - 4;
        shY = shipY - 6 * sc - SH_H * Math.max(2, sc * 2);
        shCell = Math.max(2, sc * 2);
    }

    private void startWave() {
        layout();
        al = new boolean[cols * ROWS];
        for (int i = 0; i < al.length; i++) al[i] = true;
        alive = al.length;
        fx = (W - (cols - 1) * gapX - aw) / 2;
        fy = Gfx.SMALL.getHeight() + 6 + ah + Math.min(wave, 4) * ah / 2;
        dir = 1;
        marchTimer = 0;
        shields = W >= 160 ? 4 : 3;
        shield = new boolean[shields][SH_W * SH_H];
        for (int s = 0; s < shields; s++) {
            for (int i = 0; i < SH_W * SH_H; i++) {
                int x = i % SH_W, y = i / SH_W;
                shield[s][i] = !(y == SH_H - 1 && x >= 2 && x <= 5) && !(y == 0 && (x == 0 || x == SH_W - 1));
            }
        }
        for (int i = 0; i < MAXAB; i++) abOn[i] = false;
        bulOn = false;
        px = W / 2;
        ufoOn = false;
        ufoTimer = 300;
    }

    private int shieldX(int s) {
        int total = W - 16;
        return 8 + total * (2 * s + 1) / (2 * shields) - SH_W * shCell / 2;
    }

    private boolean hitShield(int x, int y) {
        if (y < shY || y >= shY + SH_H * shCell) return false;
        for (int s = 0; s < shields; s++) {
            int sx = shieldX(s);
            if (x < sx || x >= sx + SH_W * shCell) continue;
            int cx = (x - sx) / shCell, cy = (y - shY) / shCell;
            int i = cy * SH_W + cx;
            if (shield[s][i]) {
                shield[s][i] = false;
                return true;
            }
        }
        return false;
    }

    protected void update() {
        layout();
        if (deathTimer > 0) {
            if (--deathTimer == 0) {
                if (lives <= 0) {
                    endGame(false);
                    return;
                }
                for (int i = 0; i < MAXAB; i++) abOn[i] = false;
            }
            return;
        }
        int speed = Math.max(2, W / 50);
        if ((held & K_LEFT) != 0) px -= speed;
        if ((held & K_RIGHT) != 0) px += speed;
        px = Math.max(aw / 2, Math.min(W - aw / 2, px));
        if ((pressed & (K_FIRE | K_UP)) != 0 && !bulOn) {
            bulOn = true;
            bulX = px;
            bulY = shipY - 2;
            Sfx.tone(84, 15);
        }
        if (bulOn) {
            bulY -= Math.max(4, H / 26);
            if (bulY < Gfx.SMALL.getHeight()) bulOn = false;
            else if (hitShield(bulX, bulY)) bulOn = false;
            else checkAlienHit();
        }
        march();
        if (state != PLAY) return;
        alienFire();
        ufo();
        if (alive == 0) {
            wave++;
            score += 100;
            Sfx.win();
            startWave();
        }
    }

    private void checkAlienHit() {
        if (ufoOn && bulY < Gfx.SMALL.getHeight() + ah + 4 && Math.abs(bulX - ufoX) < aw) {
            int pts = 50 * (1 + Rnd.nextInt(6));
            score += pts;
            ufoPop = 20;
            ufoPopX = ufoX;
            ufoOn = false;
            bulOn = false;
            ufoTimer = 400;
            Sfx.good();
            return;
        }
        for (int i = 0; i < al.length; i++) {
            if (!al[i]) continue;
            int x = fx + (i % cols) * gapX, y = fy + (i / cols) * gapY;
            if (bulX >= x - 1 && bulX <= x + aw && bulY >= y && bulY <= y + ah) {
                al[i] = false;
                alive--;
                bulOn = false;
                score += TYPE_PTS[ROW_TYPE[i / cols]];
                Sfx.tone(60 + (i / cols) * 3, 25);
                return;
            }
        }
    }

    private void march() {
        int interval = Math.max(1, alive * 2 / 3 - wave);
        if (++marchTimer < interval) return;
        marchTimer = 0;
        anim ^= 1;
        int minX = W, maxX = 0, maxY = 0;
        for (int i = 0; i < al.length; i++) {
            if (!al[i]) continue;
            int x = fx + (i % cols) * gapX, y = fy + (i / cols) * gapY;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + aw);
            maxY = Math.max(maxY, y + ah);
        }
        int step = Math.max(1, W / 70);
        if ((dir > 0 && maxX + step >= W - 2) || (dir < 0 && minX - step <= 2)) {
            dir = -dir;
            fy += ah / 2 + 1;
            maxY += ah / 2 + 1;
        } else {
            fx += dir * step;
        }
        Sfx.tone(40 + anim * 3, 20);
        // aliens eat the shields they touch
        for (int i = 0; i < al.length; i++) {
            if (!al[i]) continue;
            int x = fx + (i % cols) * gapX, y = fy + (i / cols) * gapY;
            for (int k = 0; k < aw; k += shCell) hitShield(x + k, y + ah - 1);
        }
        if (maxY >= shipY) {
            lives = 0;
            headline = "INVADED!";
            endGame(false);
        }
    }

    private void alienFire() {
        int chance = 3 + wave * 2 + (al.length - alive) / 6;
        if (Rnd.nextInt(100) < chance) {
            int c = Rnd.nextInt(cols);
            for (int r = ROWS - 1; r >= 0; r--) {
                int i = r * cols + c;
                if (!al[i]) continue;
                for (int k = 0; k < MAXAB; k++) {
                    if (abOn[k]) continue;
                    abOn[k] = true;
                    abx[k] = fx + c * gapX + aw / 2;
                    aby[k] = fy + r * gapY + ah;
                    break;
                }
                break;
            }
        }
        int sp = Math.max(2, H / 70) + wave / 3;
        for (int k = 0; k < MAXAB; k++) {
            if (!abOn[k]) continue;
            aby[k] += sp;
            if (aby[k] > H) abOn[k] = false;
            else if (hitShield(abx[k], aby[k])) abOn[k] = false;
            else if (aby[k] >= shipY && aby[k] <= shipY + ah && Math.abs(abx[k] - px) <= aw / 2) {
                abOn[k] = false;
                lives--;
                deathTimer = 30;
                Sfx.bad();
            }
        }
    }

    private void ufo() {
        if (ufoPop > 0) ufoPop--;
        if (!ufoOn) {
            if (--ufoTimer <= 0) {
                ufoOn = true;
                ufoX = -aw;
            }
            return;
        }
        ufoX += Math.max(1, W / 90);
        if (ufoX > W + aw) {
            ufoOn = false;
            ufoTimer = 400 + Rnd.nextInt(300);
        }
    }

    protected void draw(Graphics g) {
        layout();
        g.setColor(0x05060F);
        g.fillRect(0, 0, W, H);
        g.setColor(0x2A2D4A);
        for (int i = 0; i < 30; i++) {
            int sx = (i * 97 + 13) % W, sy = (i * 53 + clock / 4) % H;
            g.fillRect(sx, sy, 1, 1);
        }
        for (int i = 0; i < al.length; i++) {
            if (!al[i]) continue;
            int x = fx + (i % cols) * gapX, y = fy + (i / cols) * gapY;
            g.drawImage(alienImg[ROW_TYPE[i / cols]][anim], x, y, Gfx.TL);
        }
        for (int s = 0; s < shields; s++) {
            int sx = shieldX(s);
            g.setColor(0x4CD964);
            for (int i = 0; i < SH_W * SH_H; i++) {
                if (shield[s][i]) g.fillRect(sx + (i % SH_W) * shCell, shY + (i / SH_W) * shCell, shCell, shCell);
            }
        }
        if (ufoOn) {
            int y = Gfx.SMALL.getHeight() + 3;
            g.setColor(0xFF4D4D);
            g.fillArc(ufoX - aw, y + ah / 3, aw * 2, ah * 2 / 3, 0, 360);
            g.setColor(0xFFD0D0);
            g.fillArc(ufoX - aw / 3, y, aw * 2 / 3, ah * 2 / 3, 0, 180);
        }
        if (ufoPop > 0) Gfx.text(g, "BONUS", ufoPopX, Gfx.SMALL.getHeight() + 3, Gfx.TC, Gfx.SMALL_B, 0xFFD54F);
        g.setColor(0xFFFFFF);
        if (bulOn) g.fillRect(bulX - 1, bulY, 2, Math.max(4, H / 40));
        g.setColor(0xFFE066);
        for (int k = 0; k < MAXAB; k++) {
            if (!abOn[k]) continue;
            int zig = ((aby[k] / 3) & 1) == 0 ? -1 : 1;
            g.drawLine(abx[k], aby[k], abx[k] + zig, aby[k] + 3);
            g.drawLine(abx[k] + zig, aby[k] + 3, abx[k], aby[k] + 6);
        }
        if (deathTimer == 0 || (deathTimer & 2) == 0) g.drawImage(ship, px, shipY, Gfx.TC);
        g.setColor(0x4CD964);
        g.drawLine(0, H - 2, W, H - 2);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("SCORE " + score, 2, 1, Gfx.TL);
        g.setColor(0x33DDFF);
        g.drawString("x" + lives + " W" + (wave + 1), W - 2, 1, Gfx.TR);
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        layout();
        int f = (clock / 10) & 1;
        Image a = alienImg[0][f], b = alienImg[1][f], c = alienImg[2][f];
        int off = gamekit.FMath.sin(clock * 3) * (w / 8) >> 10;
        g.drawImage(c, x + w / 4 + off, y + h / 4, Gfx.CC);
        g.drawImage(a, x + w / 2 + off, y + h / 4, Gfx.CC);
        g.drawImage(b, x + w * 3 / 4 + off, y + h / 4, Gfx.CC);
        g.setColor(0x33DDFF);
        g.fillTriangle(x + w / 2, y + h - 12, x + w / 2 - 8, y + h, x + w / 2 + 8, y + h);
        g.setColor(0xFFFFFF);
        int by = y + h - 14 - (clock * 4) % Math.max(1, h - 20);
        g.fillRect(x + w / 2 - 1, by, 2, 5);
    }
}
