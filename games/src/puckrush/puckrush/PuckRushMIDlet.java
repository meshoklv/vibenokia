package puckrush;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Puck Rush - MIDlet entry point. */
public class PuckRushMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new PuckGame();
    }
}
