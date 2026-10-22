package codebreaker;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Code Breaker - MIDlet entry point. */
public class CodeBreakerMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new CodeGame();
    }
}
