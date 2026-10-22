package bubbleshot;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Bubble Shot - MIDlet entry point. */
public class BubbleShotMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new BubbleGame();
    }
}
