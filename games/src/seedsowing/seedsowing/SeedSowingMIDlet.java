package seedsowing;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Seed Sowing - MIDlet entry point. */
public class SeedSowingMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new SowGame();
    }
}
