package ladderloot;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Ladder Loot - MIDlet entry point. */
public class LadderLootMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new LootGame();
    }
}
