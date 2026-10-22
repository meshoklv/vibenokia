package slidefifteen;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Slide Fifteen - MIDlet entry point. */
public class SlideFifteenMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new SlideGame();
    }
}
