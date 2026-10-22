package gamekit;

/**
 * A moving box for tile-based games. x/y/vx/vy are in 1/256 pixel units;
 * w/h in pixels. move() resolves X then Y against a TileMap.
 */
public final class Body {
    public int x, y, vx, vy;
    public int w, h;
    public boolean ground, wallLeft, wallRight, ceiling;
    /** Set true to fall through one-way platforms this step. */
    public boolean dropThrough;

    public int px() { return x >> 8; }
    public int py() { return y >> 8; }
    public int cx() { return (x >> 8) + w / 2; }
    public int cy() { return (y >> 8) + h / 2; }

    public void place(int pxX, int pxY) {
        x = pxX << 8;
        y = pxY << 8;
        vx = vy = 0;
    }

    public boolean overlaps(Body o) {
        int ax = x >> 8, ay = y >> 8, bx = o.x >> 8, by = o.y >> 8;
        return ax < bx + o.w && bx < ax + w && ay < by + o.h && by < ay + h;
    }

    public void move(TileMap m) {
        int ts = m.ts;
        wallLeft = wallRight = ceiling = false;
        if (vx != 0) {
            int nx = x + vx;
            int top = m.tile(y >> 8), bot = m.tile((y >> 8) + h - 1);
            if (vx > 0) {
                int c = m.tile((nx >> 8) + w - 1);
                for (int r = top; r <= bot; r++) {
                    if (m.isSolid(c, r)) {
                        nx = (c * ts - w) << 8;
                        vx = 0;
                        wallRight = true;
                        break;
                    }
                }
            } else {
                int c = m.tile(nx >> 8);
                for (int r = top; r <= bot; r++) {
                    if (m.isSolid(c, r)) {
                        nx = ((c + 1) * ts) << 8;
                        vx = 0;
                        wallLeft = true;
                        break;
                    }
                }
            }
            x = nx;
        }
        ground = false;
        if (vy != 0) {
            int ny = y + vy;
            int left = m.tile(x >> 8), right = m.tile((x >> 8) + w - 1);
            if (vy > 0) {
                int prevBottom = (y >> 8) + h; // first pixel row below the body
                // check the row the new bottom pixel is in, and the row just below
                // when resting exactly on a tile edge (sub-pixel gravity steps)
                int below = (ny >> 8) + h;
                int r0 = m.tile(below - 1);
                int rEnd = m.tile(below) * ts == below ? r0 + 1 : r0;
                for (int r = r0; r <= rEnd && !ground; r++) {
                    for (int c = left; c <= right; c++) {
                        boolean block = m.isSolid(c, r)
                                || (!dropThrough && m.isOneWay(c, r) && prevBottom <= r * ts);
                        if (block) {
                            ny = (r * ts - h) << 8;
                            vy = 0;
                            ground = true;
                            break;
                        }
                    }
                }
            } else {
                int r = m.tile(ny >> 8);
                for (int c = left; c <= right; c++) {
                    if (m.isSolid(c, r)) {
                        ny = ((r + 1) * ts) << 8;
                        vy = 0;
                        ceiling = true;
                        break;
                    }
                }
            }
            y = ny;
        }
        dropThrough = false;
    }
}
