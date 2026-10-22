package fivestones;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Five Stones - MIDlet entry point. */
public class FiveStonesMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new StonesGame();
    }
}
