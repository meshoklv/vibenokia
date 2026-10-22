package codebreaker;

import gamekit.Game;
import gamekit.Gfx;
import gamekit.Rnd;
import gamekit.Sfx;
import javax.microedition.lcdui.Graphics;

/**
 * Code Breaker: deduce a hidden code of coloured pegs. After each guess you
 * learn how many pegs are the right colour in the right place (black) and
 * the right colour in the wrong place (white).
 */
public class CodeGame extends Game {
    private static final int[] COL = { 0xE53935, 0x43A047, 0x1E88E5, 0xFDD835, 0x8E24AA, 0xFB8C00, 0x00ACC1, 0xF5F5F5 };
    private int pegs, colours, maxTries, tries, pos;
    private int[] code;
    private int[][] guess;
    private int[] black, white;
    private boolean revealed;

    protected String name() { return "Code Breaker"; }

    protected String[] help() {
        return new String[] {
            "The phone hides a secret code of coloured pegs. Crack it in as few guesses as you can.",
            "After each guess, a black marker means a peg of the right colour in the right place; a white marker means right colour, wrong place. Colours may repeat.",
            "Press the number keys 1-6 (or 1-8) to set the peg under the cursor straight to that colour; the cursor moves on automatically. Press # or 0 to submit.",
            "- Controls",
            "1-8: set colour",
            "# or 0: submit guess",
            "Joystick: move / cycle / select",
        };
    }

    protected String[] modes() { return new String[] { "4 pegs, 6 colours", "5 pegs, 8 colours" }; }

    protected boolean lowerIsBetter() { return true; }

    protected String formatScore(int s) { return s + " guesses"; }

    protected int accent() { return 0x8E24AA; }

    protected void newGame() {
        pegs = mode == 0 ? 4 : 5;
        colours = mode == 0 ? 6 : 8;
        maxTries = mode == 0 ? 10 : 12;
        code = new int[pegs];
        for (int i = 0; i < pegs; i++) code[i] = Rnd.nextInt(colours);
        guess = new int[maxTries][pegs];
        black = new int[maxTries];
        white = new int[maxTries];
        tries = 0;
        pos = 0;
        revealed = false;
        for (int i = 0; i < pegs; i++) guess[0][i] = -1;
    }

    protected void update() {
        if (tries >= maxTries) return;
        int[] g = guess[tries];
        int d = digitPressed();
        if (d >= 1 && d <= colours) {
            // number keys pick a colour directly and advance the cursor
            g[pos] = d - 1;
            pos = (pos + 1) % pegs;
            Sfx.tone(60 + d * 2, 20);
        } else if (d < 0) {
            // joystick only (digit presses also carry direction bits)
            if ((pressed & K_LEFT) != 0) pos = (pos + pegs - 1) % pegs;
            if ((pressed & K_RIGHT) != 0) pos = (pos + 1) % pegs;
            if ((pressed & K_UP) != 0) g[pos] = (g[pos] + 1 + colours) % colours;
            if ((pressed & K_DOWN) != 0) g[pos] = (Math.max(0, g[pos]) - 1 + colours) % colours;
            if ((pressed & K_FIRE) != 0) submit();
        }
        if (d == 0 || (pressed & K_POUND) != 0) submit();
    }

    private void submit() {
        int[] g = guess[tries];
        for (int i = 0; i < pegs; i++) if (g[i] < 0) { Sfx.bad(); return; }
        int b = 0, w = 0;
        boolean[] usedC = new boolean[pegs], usedG = new boolean[pegs];
        for (int i = 0; i < pegs; i++) if (g[i] == code[i]) { b++; usedC[i] = usedG[i] = true; }
        for (int i = 0; i < pegs; i++) {
            if (usedG[i]) continue;
            for (int j = 0; j < pegs; j++) {
                if (!usedC[j] && g[i] == code[j]) { w++; usedC[j] = true; break; }
            }
        }
        black[tries] = b;
        white[tries] = w;
        tries++;
        Sfx.click();
        if (b == pegs) {
            revealed = true;
            score = tries;
            headline = "CRACKED IN " + tries;
            endGame(true);
            return;
        }
        if (tries >= maxTries) {
            revealed = true;
            headline = "CODE NOT CRACKED";
            endGame(false);
            return;
        }
        for (int i = 0; i < pegs; i++) guess[tries][i] = g[i];
        pos = 0;
    }

    protected void draw(Graphics g) {
        int hud = Gfx.SMALL.getHeight() + 2;
        g.setColor(0x3E2723);
        g.fillRect(0, 0, W, H);
        int rows = maxTries + 1;
        int rh = Math.max(7, (H - hud * 2) / rows);
        int r = Math.max(2, Math.min(rh / 2 - 1, W / (pegs * 3)));
        int pegX0 = W / 6, step = (W * 3 / 5) / pegs;
        // secret row
        int y = hud;
        for (int i = 0; i < pegs; i++) {
            int x = pegX0 + i * step;
            g.setColor(revealed ? COL[code[i]] : 0x212121);
            Gfx.disc(g, x, y + rh / 2, r);
            if (!revealed) {
                g.setColor(0x757575);
                g.drawString("?", x, y + rh / 2 - 4, Gfx.TC);
            }
        }
        for (int t = 0; t < maxTries; t++) {
            int yy = hud + (t + 1) * rh;
            boolean active = t == tries && state == PLAY;
            if (active) {
                g.setColor(0x5D4037);
                g.fillRect(0, yy, W, rh);
            }
            if (t > tries) continue;
            for (int i = 0; i < pegs; i++) {
                int x = pegX0 + i * step, v = guess[t][i];
                if (v >= 0) {
                    g.setColor(COL[v]);
                    Gfx.disc(g, x, yy + rh / 2, r);
                } else {
                    g.setColor(0x6D4C41);
                    Gfx.ring(g, x, yy + rh / 2, r);
                }
                if (active && i == pos) {
                    g.setColor((clock & 4) == 0 ? 0xFFFFFF : 0xFFEB3B);
                    Gfx.ring(g, x, yy + rh / 2, r + 1);
                }
            }
            if (t < tries) {
                int kx = W * 5 / 6;
                for (int k = 0; k < pegs; k++) {
                    int c = k < black[t] ? 0x000000 : (k < black[t] + white[t] ? 0xFFFFFF : 0x5D4037);
                    g.setColor(c);
                    g.fillRect(kx + (k % 3) * 4, yy + rh / 2 - 3 + (k / 3) * 4, 3, 3);
                }
            }
        }
        // colour key legend
        int ly = H - hud;
        for (int c = 0; c < colours; c++) {
            int x = 4 + c * (W - 8) / colours;
            g.setColor(COL[c]);
            g.fillRect(x, ly + 2, 6, 6);
            g.setFont(Gfx.SMALL);
            g.setColor(0xD7CCC8);
            g.drawString(String.valueOf(c + 1), x + 8, ly, Gfx.TL);
        }
        g.setFont(Gfx.SMALL_B);
        g.setColor(0xFFFFFF);
        g.drawString("Guess " + Math.min(tries + 1, maxTries) + "/" + maxTries, 2, 1, Gfx.TL);
    }

    protected void drawTitleArt(Graphics gr, int x, int y, int w, int h) {
        for (int i = 0; i < 4; i++) {
            gr.setColor(COL[(i + clock / 10) % 6]);
            Gfx.disc(gr, x + w / 5 + i * w / 5, y + h / 2, Math.max(4, h / 5));
        }
    }
}
