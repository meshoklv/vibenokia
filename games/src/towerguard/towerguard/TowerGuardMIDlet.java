package towerguard;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Tower Guard - MIDlet entry point. */
public class TowerGuardMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new TowerGame();
    }
}
