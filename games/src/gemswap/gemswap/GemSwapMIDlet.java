package gemswap;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Gem Swap - MIDlet entry point. */
public class GemSwapMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new GemGame();
    }
}
