package fourdrop;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Four Drop - MIDlet entry point. */
public class FourDropMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new FourDropGame();
    }
}
