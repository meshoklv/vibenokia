package rockblaster;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Rock Blaster - MIDlet entry point. */
public class RockBlasterMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new RockGame();
    }
}
