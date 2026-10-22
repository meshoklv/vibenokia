package pixellogic;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Pixel Logic - MIDlet entry point. */
public class PixelLogicMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new LogicGame();
    }
}
