package lasermirrors;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Laser Mirrors - MIDlet entry point. */
public class LaserMirrorsMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new LaserGame();
    }
}
