package puckrush;

import gamekit.FMath;
import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Puck Rush: table air hockey against the CPU. Positions are 8.8 fixed
 * point in screen pixels; mallet-puck collisions push the puck away along
 * the line between centres and add the mallet's own velocity.
 */
public class PuckGame extends Game {
    private int px, py, pvx, pvy;           // puck
    private int mx, my, mvx, mvy;           // player mallet
    private int cx, cy, cvx, cvy;           // cpu mallet
    private int pr, mr, goalW, top, bot, myGoals, cpuGoals, serve, flash;

    protected String name() { return "Puck Rush"; }

    protected String[] help() {
        return new String[] {
            "Air hockey on a keypad. Slide your mallet around your half of the table and smash the puck into the CPU's goal at the top.",
            "Hitting the puck while your mallet is moving adds power. The puck slows slightly over time and bounces off the side rails.",
            "First to 7 goals wins.",
            "- Controls",
            "2/4/6/8: move mallet",
            "1/3/7/9: move diagonally",
        };
    }

    protected String[] modes() { return new String[] { "Casual", "Pro" }; }

    protected int accent() { return 0x00E5FF; }

    protected void newGame() {
        myGoals = cpuGoals = 0;
        layout();
        resetRound(0);
    }

    private void layout() {
        int hud = Gfx.SMALL.getHeight() + 2;
        top = hud;
        bot = H;
        pr = Math.max(3, W / 28);
        mr = Math.max(5, W / 16);
        goalW = W * 2 / 5;
    }

    private void resetRound(int toward) {
        px = (W / 2) << 8;
        py = ((top + bot) / 2 + (toward == 0 ? 10 : -10)) << 8;
        pvx = pvy = 0;
        mx = (W / 2) << 8;
        my = (bot - mr * 3) << 8;
        cx = (W / 2) << 8;
        cy = (top + mr * 3) << 8;
        mvx = mvy = cvx = cvy = 0;
        serve = 20;
    }

    private void collide(int ax, int ay, int avx, int avy) {
        int dx = (px - ax) >> 8, dy = (py - ay) >> 8;
        int min = pr + mr;
        int d2 = dx * dx + dy * dy;
        if (d2 >= min * min) return;
        int d = Math.max(1, FMath.sqrt(d2));
        // push puck out of the mallet
        px = ax + ((dx * min / d) << 8);
        py = ay + ((dy * min / d) << 8);
        // reflect relative velocity along the normal, add mallet velocity
        int rvx = pvx - avx, rvy = pvy - avy;
        int dot = (rvx * dx + rvy * dy) / d;
        if (dot < 0) {
            pvx -= 2 * dot * dx / d;
            pvy -= 2 * dot * dy / d;
        }
        pvx += avx / 2;
        pvy += avy / 2;
        if (dx == 0 && dy == 0) pvy = ay < py ? 512 : -512;
        Sfx.hit();
    }

    protected void update() {
        if (W != 0 && goalW != W * 2 / 5) layout();
        if (flash > 0) flash--;
        int speed = mode == 1 ? 3 << 8 : (5 << 8) / 2;
        int max = Math.max(4, W / 24) << 8;
        // player mallet
        int ax = 0, ay = 0;
        if ((held & K_LEFT) != 0 || (held & ((K_NUM0 << 1) | (K_NUM0 << 7))) != 0) ax = -1;
        if ((held & K_RIGHT) != 0 || (held & ((K_NUM0 << 3) | (K_NUM0 << 9))) != 0) ax = 1;
        if ((held & K_UP) != 0 || (held & ((K_NUM0 << 1) | (K_NUM0 << 3))) != 0) ay = -1;
        if ((held & K_DOWN) != 0 || (held & ((K_NUM0 << 7) | (K_NUM0 << 9))) != 0) ay = 1;
        int pms = max * 3 / 4;
        mvx = ax * pms;
        mvy = ay * pms;
        int omx = mx, omy = my;
        mx = FMath.clamp(mx + mvx, mr << 8, (W - mr) << 8);
        my = FMath.clamp(my + mvy, ((top + bot) / 2 + mr) << 8, (bot - mr) << 8);
        mvx = mx - omx;
        mvy = my - omy;
        // CPU mallet: chases the puck in its half, otherwise guards the goal
        int tx, ty;
        if (py < ((top + bot) / 2) << 8 && pvy < 256) {
            tx = px;
            ty = py - (pr << 8);
        } else {
            tx = (W / 2 << 8) + (px - (W / 2 << 8)) / 3;
            ty = (top + mr * 2) << 8;
        }
        int cs = mode == 1 ? speed : speed * 2 / 3;
        int ocx = cx, ocy = cy;
        cx += FMath.clamp(tx - cx, -cs, cs);
        cy += FMath.clamp(ty - cy, -cs, cs);
        cx = FMath.clamp(cx, mr << 8, (W - mr) << 8);
        cy = FMath.clamp(cy, (top + mr) << 8, ((top + bot) / 2 - mr) << 8);
        cvx = cx - ocx;
        cvy = cy - ocy;
        if (serve > 0) {
            serve--;
            return;
        }
        // puck
        px += pvx;
        py += pvy;
        pvx = pvx * 253 >> 8;
        pvy = pvy * 253 >> 8;
        collide(mx, my, mvx * 2, mvy * 2);
        collide(cx, cy, cvx * 2, cvy * 2);
        pvx = FMath.clamp(pvx, -max * 2, max * 2);
        pvy = FMath.clamp(pvy, -max * 2, max * 2);
        if (px < pr << 8) { px = pr << 8; pvx = -pvx; }
        if (px > (W - pr) << 8) { px = (W - pr) << 8; pvx = -pvx; }
        boolean inMouth = px > ((W - goalW) / 2) << 8 && px < ((W + goalW) / 2) << 8;
        if (py < (top + pr) << 8) {
            if (inMouth && py < top << 8) {
                myGoals++;
                score += 100;
                flash = 20;
                Sfx.good();
                if (myGoals >= 7) {
                    score += 500 - cpuGoals * 50;
                    headline = "YOU WIN " + myGoals + "-" + cpuGoals;
                    endGame(true);
                    return;
                }
                resetRound(1);
            } else if (!inMouth) {
                py = (top + pr) << 8;
                pvy = -pvy;
            }
        }
        if (py > (bot - pr) << 8) {
            if (inMouth && py > bot << 8) {
                cpuGoals++;
                flash = -20;
                Sfx.bad();
                if (cpuGoals >= 7) {
                    headline = "CPU WINS " + cpuGoals + "-" + myGoals;
                    endGame(false);
                    return;
                }
                resetRound(0);
            } else if (!inMouth) {
                py = (bot - pr) << 8;
                pvy = -pvy;
            }
        }
        // nudge a stalled puck
        if (Math.abs(pvx) + Math.abs(pvy) < 20 && frame % 60 == 0) pvx += Rnd.range(-128, 129);
    }

    protected void draw(Graphics g) {
        if (goalW == 0) layout();
        int hud = top;
        g.setColor(0x0D1B2A);
        g.fillRect(0, 0, W, H);
        g.setColor(0xE0F7FA);
        g.fillRect(0, top, W, bot - top);
        g.setColor(0x80DEEA);
        int mid = (top + bot) / 2;
        g.drawLine(0, mid, W, mid);
        int cr = W / 6;
        g.drawArc(W / 2 - cr, mid - cr, cr * 2, cr * 2, 0, 360);
        g.setColor(0xEF5350);
        g.fillRect((W - goalW) / 2, top, goalW, 3);
        g.setColor(0x1E88E5);
        g.fillRect((W - goalW) / 2, bot - 3, goalW, 3);
        g.setColor(0xB0BEC5);
        g.drawArc((W - goalW) / 2, top - goalW / 4, goalW, goalW / 2, 180, 180);
        g.drawArc((W - goalW) / 2, bot - goalW / 4, goalW, goalW / 2, 0, 180);
        // puck
        g.setColor(0x212121);
        Gfx.disc(g, px >> 8, py >> 8, pr);
        // mallets
        g.setColor(0xD32F2F);
        Gfx.disc(g, cx >> 8, cy >> 8, mr);
        g.setColor(0xFFCDD2);
        Gfx.disc(g, cx >> 8, cy >> 8, mr / 2);
        g.setColor(0x1565C0);
        Gfx.disc(g, mx >> 8, my >> 8, mr);
        g.setColor(0xBBDEFB);
        Gfx.disc(g, mx >> 8, my >> 8, mr / 2);
        g.setColor(0x000000);
        g.fillRect(0, 0, W, hud);
        g.setFont(Gfx.SMALL_B);
        g.setColor(0x64B5F6);
        g.drawString("YOU " + myGoals, 2, 1, Gfx.TL);
        g.setColor(0xEF9A9A);
        g.drawString(cpuGoals + " CPU", W - 2, 1, Gfx.TR);
        g.setColor(0xFFFFFF);
        g.drawString("to 7", W / 2, 1, Gfx.TC);
        if (flash != 0) {
            Gfx.shadowText(g, "GOAL!", W / 2, mid - Gfx.LARGE.getHeight() / 2, Gfx.TC, Gfx.LARGE, flash > 0 ? 0x1565C0 : 0xC62828, 0xFFFFFF);
        }
    }

    protected void drawTitleArt(Graphics g, int x, int y, int w, int h) {
        g.setColor(0xE0F7FA);
        g.fillRect(x, y, w, h);
        g.setColor(0x80DEEA);
        g.drawLine(x, y + h / 2, x + w, y + h / 2);
        int r = Math.max(3, h / 8);
        int t = clock % 40, bx = x + r + (t < 20 ? t : 40 - t) * (w - r * 2) / 20;
        g.setColor(0x212121);
        Gfx.disc(g, bx, y + h / 2, r / 2 + 1);
        g.setColor(0x1565C0);
        Gfx.disc(g, x + r + 2, y + h / 2, r);
        g.setColor(0xD32F2F);
        Gfx.disc(g, x + w - r - 2, y + h / 2, r);
    }
}
