package drawpoker;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Draw Poker - MIDlet entry point. */
public class DrawPokerMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new PokerGame();
    }
}
