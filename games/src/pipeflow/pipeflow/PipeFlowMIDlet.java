package pipeflow;

import gamekit.Game;
import gamekit.GameMIDlet;

/** Pipe Flow - MIDlet entry point. */
public class PipeFlowMIDlet extends GameMIDlet {
    protected Game createGame() {
        return new PipeGame();
    }
}
