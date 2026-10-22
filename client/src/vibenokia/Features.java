package vibenokia;

/**
 * Optional features. By default the app is a simple Claude chat plus Settings;
 * everything else is switched on in Settings > Features (stored in RMS).
 *
 * A feature is shown only if BOTH the user enabled it (local) AND the server
 * allows it (server, from the "features" field of /ping, set by the server's
 * VIBENOKIA_FEATURES). Until the server answers, everything is allowed.
 */
final class Features {
    static final int TELEGRAM = 1;   // Telegram tile, contacts, unread badge
    static final int OPENCODE = 2;   // OpenCode chat tile (free models)
    static final int SESSIONS = 4;   // session list: switch / rename / delete
    static final int VOICE    = 8;   // hold * to record a voice message
    static final int ADVANCED = 16;  // servers list, app update, key rotation, re-pairing
    static final int ALL      = 31;
    static final int DEFAULT  = 0;   // simple mode: chat + settings only

    static int local = DEFAULT;
    static int server = ALL;

    private Features() {}

    static boolean on(int f) { return (local & server & f) != 0; }

    /** Server-side names in the /ping "features" field. */
    static final String[] NAMES = { "telegram", "opencode", "sessions", "voice", "advanced" };

    static int parseServer(String csv) {
        int m = 0;
        for (int i = 0; i < NAMES.length; i++)
            if (csv.indexOf(NAMES[i]) >= 0) m |= (1 << i);
        return m;
    }

    static String label(int bit) {
        switch (bit) {
            case TELEGRAM: return "Telegram";
            case OPENCODE: return "OpenCode";
            case SESSIONS: return Strings.sessions();
            case VOICE:    return Strings.featVoice();
            default:       return Strings.featAdvanced();
        }
    }
}
