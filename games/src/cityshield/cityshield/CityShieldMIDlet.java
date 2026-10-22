package cityshield;

import gamekit.Game;
import gamekit.GameMIDlet;

/** City Shield - MIDlet entry point. */
public class CityShieldMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new ShieldGame();
    }
}
