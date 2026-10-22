package coptercave;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Copter Cave - MIDlet entry point. */
public class CopterCaveMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new CopterGame();
    }
}
