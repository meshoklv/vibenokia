package keypadchess;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Keypad Chess - MIDlet entry point. */
public class KeypadChessMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new ChessGame();
    }
}
