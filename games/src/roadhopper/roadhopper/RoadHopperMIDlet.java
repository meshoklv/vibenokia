package roadhopper;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Road Hopper - MIDlet entry point. */
public class RoadHopperMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new HopperGame();
    }
}
