package cellsolitaire;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Cell Solitaire - MIDlet entry point. */
public class CellSolitaireMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new CellGame();
    }
}
