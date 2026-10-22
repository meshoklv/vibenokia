package pegjump;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Peg Jump - MIDlet entry point. */
public class PegJumpMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new PegGame();
    }
}
