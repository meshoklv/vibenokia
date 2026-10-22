package twentyone;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Twenty-One - MIDlet entry point. */
public class TwentyOneMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new TwentyOneGame();
    }
}
