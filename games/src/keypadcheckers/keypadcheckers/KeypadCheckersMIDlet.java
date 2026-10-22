package keypadcheckers;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Keypad Checkers - MIDlet entry point. */
public class KeypadCheckersMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new CheckersGame();
    }
}
