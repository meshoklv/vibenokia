package eightsshed;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Eights Shed - MIDlet entry point. */
public class EightsShedMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new EightsGame();
    }
}
