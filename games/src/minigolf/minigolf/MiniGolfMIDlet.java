package minigolf;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Mini Golf - MIDlet entry point. */
public class MiniGolfMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new GolfGame();
    }
}
