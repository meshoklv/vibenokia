package stardefender;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Star Defender - MIDlet entry point. */
public class StarDefenderMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new DefenderGame();
    }
}
