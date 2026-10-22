package seastrike;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Sea Strike - MIDlet entry point. */
public class SeaStrikeMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new SeaStrikeGame();
    }
}
