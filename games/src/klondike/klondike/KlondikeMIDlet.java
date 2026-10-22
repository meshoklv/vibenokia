package klondike;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Klondike - MIDlet entry point. */
public class KlondikeMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new KlondikeGame();
    }
}
