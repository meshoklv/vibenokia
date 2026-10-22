package numberplace;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Number Place - MIDlet entry point. */
public class NumberPlaceMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new NumberPlaceGame();
    }
}
