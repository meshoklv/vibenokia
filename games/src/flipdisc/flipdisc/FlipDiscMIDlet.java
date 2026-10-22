package flipdisc;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Flip Disc - MIDlet entry point. */
public class FlipDiscMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new FlipGame();
    }
}
