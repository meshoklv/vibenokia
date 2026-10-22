package molebonk;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Mole Bonk - MIDlet entry point. */
public class MoleBonkMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new MoleGame();
    }
}
