package gamekit;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

/**
 * Base MIDlet shared by every game. A game's MIDlet only has to say which
 * Game canvas to create; lifecycle, pausing and exiting are handled here.
 */
public abstract class GameMIDlet extends MIDlet {
    private Game game;

    /** Create the game's canvas. Called once, on the first startApp(). */
    protected abstract Game createGame();

    protected void startApp() {
        if (game == null) {
            game = createGame();
            game.attach(this);
        }
        Display.getDisplay(this).setCurrent(game);
        game.start();
    }

    protected void pauseApp() {
        if (game != null) game.systemPause();
    }

    protected void destroyApp(boolean unconditional) {
        if (game != null) game.stop();
    }

    /** Leave the application. */
    public void exit() {
        destroyApp(true);
        notifyDestroyed();
    }
}
