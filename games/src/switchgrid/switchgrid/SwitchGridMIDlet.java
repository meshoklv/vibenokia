package switchgrid;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Switch Grid - MIDlet entry point. */
public class SwitchGridMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new SwitchGame();
    }
}
