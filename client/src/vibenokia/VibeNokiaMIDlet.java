package vibenokia;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.media.*;
import javax.microedition.media.control.*;
import java.io.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.rms.*;

public class VibeNokiaMIDlet extends MIDlet implements CommandListener {
    private Display display;

    private TerminalCanvas terminal;
    private InputCanvas inputCanvas;     // text input directly on a Canvas: Form does not deliver keyRepeated (cancel on 0)
    private MenuCanvas settingsMenu;     // settings are on a Canvas too: Form has no keyRepeated -> no cancel
    private MenuCanvas sessionsList;
    private MenuCanvas menuList;

    private Command sendCmd, backCmd, saveCmd, menuCmd;
    private Command openItemCmd, cycleCmd;
    private Command voiceSendCmd, voiceCancelCmd, cancelRecordCmd, stopCmd;

    // Settings draft: the value is cycled right in the list row and applied on "Save"
    private int tmpLang = 0, tmpVoice = 0, tmpFont = 0;
    private int fontSize = 0;   // 0 = small, 1 = medium

    // --- Text input (InputCanvas): routing by purpose ---
    private static final int IP_CHAT = 0;          // message to the Claude/OpenCode chat or Telegram
    private static final int IP_SERVER_NAME = 1;   // server form, step 1: name
    private static final int IP_SERVER_URL = 2;    // server form, step 2: address
    private static final int IP_TG_ID = 3;         // Telegram contact, step 1: chat_id
    private static final int IP_TG_NAME = 4;       // Telegram contact, step 2: name
    private static final int IP_PAIR = 5;          // one-time phone pairing code
    private int inputPurpose = IP_CHAT;
    private String chatDraft = "";                 // an unsent message waits for next time
    private String pendingServerName = "";         // value of step 1 of the server form
    private String pendingTgId = "";               // value of step 1 of the Telegram contact
    private final Vector inputHistory = new Vector();   // history of sent messages (up/down in the input)

    private String serverUrl = DEFAULT_SERVER_URL;
    private String sessionId = "";
    private boolean isLoading = false;
    private String currentModel = "opus";

    // --- Home screen and panels ---
    private HomeCanvas home;
    public static final int PANEL_NONE = -1;
    public static final int PANEL_TG = 0;
    public static final int PANEL_CLAUDE = 1;
    public static final int PANEL_OC = 2;
    private int activePanel = PANEL_NONE;   // where the user currently is
    private int chatOwner = PANEL_NONE;     // whose content is in the terminal

    // Model and session - each chat panel has its own
    private String claudeModel = "opus";
    private String ocModel = "opencode/big-pickle";
    private String claudeSessionId = "";
    private String ocSessionId = "";

    private int pingFlip = 0;               // alternating ping targets
    private Command homeOpenCmd, homeExitCmd;
    private String[] pickIds;               // snapshot of the lists while picking
    private String[] pickNames;

    private Player audioPlayer;
    private RecordControl recordControl;
    private ByteArrayOutputStream audioStream;
    private volatile boolean isRecording = false;
    private final Object recLock = new Object();   // exactly one thread starts/commits a recording
    private int recordTimer = 0;
    private Thread timerThread;

    private String[] sessionIds;
    private String[] sessionNames;
    private Timer pingTimer;
    private int voiceMode = 0; // 0=star hold, 1=OK press
    private HttpConnection activeConn;
    private volatile int reqEpoch = 0;   // grows on start/cancel; a stale thread must not touch the screen

    // --- Servers: a list with quick switching (key 1 / Menu) ---
    // Replaced at build time (make SERVER_URL=...); can also be edited on the phone: Settings > Servers.
    static final String DEFAULT_SERVER_URL = "http://YOUR_SERVER:8085";
    private String[] serverNames = {"Telegram", "Claude"};
    private String[] serverUrls = {DEFAULT_SERVER_URL, DEFAULT_SERVER_URL};
    // The CURRENT LIVE address of the "Claude" role (null = the primary one by name/port).
    // Changed only by auto-probing (timer/manual ping) and by manual server choice;
    // stored in the "netstate" RMS so that after a restart we do not knock on a dead one.
    private volatile String liveFullUrl = null;
    private int fullFails = 0;                   // consecutive failures of the current Claude address
    private static final int FULL_FAILS_TO_SWITCH = 2;   // hysteresis: do not flap on a single loss
    private boolean backupMigrated = false;      // the backup "Claude-2" entry has been appended once (RMS)
    private final Object pingLock = new Object();        // one ping at a time
    // Connection of the current ping: a user request ABORTS it instead of queueing behind it
    // (a hanging ping on J9 holds the only channel until the stack timeout - minutes)
    private volatile HttpConnection pingConn = null;
    private volatile boolean pingAborted = false;        // ping aborted by a request - do not count the result
    private int serverIdx = 0;
    private String serverMode = "";      // "tg" or "full" - what the active server can do
    private MenuCanvas serversList;
    private int editingServer = -1;      // -1 = adding a new one

    // --- Telegram ---
    private boolean tgMode = false;      // true = the terminal shows a Telegram conversation with a contact
    private String tgChatId = "";
    private String tgTitle = "";
    private String tgBotName = "";       // @bot name - shown so it can be given to friends
    private String[] tgIds;
    private String[] tgLabels;
    private MenuCanvas tgList;

    static final String APP_VERSION = "2.7.0";   // package-private: read by HomeCanvas
    // The server does not check the UA (it only logs its own in outgoing requests); relays are plain TCP.
    private static final String USER_AGENT = "VibeNokia/2.7";
    // There is NO key in the application anymore. The phone is paired once with a
    // code and receives its own key, which lives in phone memory; every
    // request is signed with it (DeviceAuth). A leaked build gives nothing.
    // Model list. A base set is built in (works offline), but the real
    // source is the server (/api/models): at startup and on EVERY opening of the
    // model picker. OpenCode - ONLY free models (cost 0); the server picks up
    // the fresh free pool itself and hides broken ones. There are no paid models in the client.
    private String[] MODEL_NAMES = {
        "Opus 5", "Sonnet 5", "Haiku 4.5",
        "Big Pickle", "Ling 3.1", "LongCat 2.5", "MiMo 2.6",
        "Muse 1.3", "Nemo 3 Ultra", "Nemo 3.5 Light", "Space Bunny"
    };
    private String[] MODEL_IDS = {
        "opus", "sonnet", "haiku",
        "opencode/big-pickle", "opencode/ling-3.1-flash-free",
        "opencode/longcat-2.5-preview-free", "opencode/mimo-v2.6-flash-free",
        "opencode/muse-spark-1.3-contributor-free", "opencode/nemotron-3-ultra-free",
        "opencode/nemotron-3.5-lightning-free", "opencode/space-bunny-free"
    };
    // Default free OpenCode model - named by the server (the first live one)
    private String ocDefault = "opencode/big-pickle";

    private boolean started = false;
    private boolean active = false;      // true while the app is running; background workers check it
    private boolean netBooted = false;   // network start already done (exactly once)

    public void startApp() {
        display = Display.getDisplay(this);
        // startApp is also called on RETURN from a call/background - do not recreate everything,
        // otherwise the chat is erased and ping timers multiply with every call
        if (started) {
            // goHome rather than a bare setCurrent: it resets tgMode - otherwise after a call
            // the ping would quietly "read" the open Telegram chat and swallow unread messages
            goHome();
            if (pingTimer == null && DeviceAuth.isPaired()) startPingTimer();
            return;
        }
        started = true;
        active = true;
        loadSettings();
        loadFeatures();
        loadNetState();    // BEFORE loadServers: it needs the one-time "Claude-2" migration flag
        loadServers();
        buildCommands();
        initUI();
        if (fontSize != 0) terminal.setFontSize(fontSize);
        startSession();
    }

    public void pauseApp() {
        // call/minimize: release the microphone and do not ping from background
        stopRecording(false);
        if (pingTimer != null) { pingTimer.cancel(); pingTimer = null; }
    }

    public void destroyApp(boolean unconditional) {
        active = false;
        if (pingTimer != null) { pingTimer.cancel(); pingTimer = null; }
        stopRecording(false);
        closePlayer();
        notifyDestroyed();
    }

    private int tgAutoTick = 0;   // ping tick counter for auto-refreshing the Telegram chat
    private int tgUnread = 0;     // unread count from the last ping - for "Telegram (N)"
    private int lastShownTgId = 0;   // id of the last shown preview (deduplication)
    private String lastTgFrom = "";  // latest sender - for "About"
    private String lastTgChat = "";  // chat_id of the latest unread message

    private void startPingTimer() { startPingTimer(0); }

    private void startPingTimer(long delay) {
        if (pingTimer != null) pingTimer.cancel();
        pingTimer = new Timer();
        pingTimer.schedule(new TimerTask() {
            public void run() {
                // a RuntimeException in a tick must not kill the Timer (it dies forever)
                // (the ping mutex inside pingTick is released in finally - nothing to clean up here)
                try { pingTick(); } catch (Exception e) {}
            }
        }, delay, 5000);
    }

    private int fullSearchPos = -1;   // >=0: searching for a live Claude server, one candidate per tick
    // Claude did not answer - ping it once every FULL_DOWN_EVERY "Claude" ticks until the first
    // success (ticks alternate TG/Claude, 3 Claude turns = ~30 s;
    // without a TG slot ~15 s): a dead address holds the channel until timeout, and on J9 an abort from another thread
    // may not work. The remaining ticks go to the TG role (pinged as before).
    private volatile boolean fullDown = false;
    private int fullSkip = 0;
    private static final int FULL_DOWN_EVERY = 3;

    /** One background ping tick. Holds the connection for at most ONE timeout. */
    private void pingTick() {
        if (!active) return;   // cancel: the timer may not have been cancelled in time - stay silent
        final int myEpoch = reqEpoch;   // epoch at tick start: cancel in the middle of the network call silences the vibrations below
        // do not take the single HTTP connection from a request or a background Telegram refresh
        if (isLoading || autoRefreshing) return;
        // In the Telegram chat, pull new messages ourselves every ~15 s
        if (tgMode && display.getCurrent() == terminal) {
            tgAutoTick++;
            if (tgAutoTick >= 3) {
                tgAutoTick = 0;
                loadTgMessages(false);
                return;
            }
        } else {
            tgAutoTick = 0;
        }
        // Targets: unread always from the TG role; the connectivity dot from the section's server.
        // On the home screen alternate BOTH roles - otherwise the "Claude" dot at the bottom is dead.
        // No TG slot (tgU empty) - ping only Claude.
        String tgU = tgServerUrl();
        boolean hasTg = tgU.length() > 0 && Features.on(Features.TELEGRAM);
        String fullU = fullServerUrl();
        String secU = (activePanel == PANEL_TG && hasTg) ? tgU : fullU;
        boolean askTg = hasTg && ((pingFlip++ % 2 == 0) || secU.equals(tgU));
        String target = askTg ? tgU : secU;
        boolean isFull = !askTg && target.equals(fullU);
        if (isFull && fullDown && ++fullSkip < FULL_DOWN_EVERY) {
            if (!hasTg) return;                      // Claude is silent, no TG slot - empty tick
            askTg = true; target = tgU; isFull = false;   // this tick goes to the TG role
        }
        if (isFull) fullSkip = 0;

        // Search for a live Claude: ONE candidate per tick (current -> next tick the reserve),
        // so that chat/voice in waitNetIdle wait no longer than one timeout
        String probeUrl = target;
        boolean searching = false;
        if (isFull && fullSearchPos >= 0) {
            String c = nextFullCandidate(target);
            if (c != null) { probeUrl = c; searching = true; }
            else fullSearchPos = -1;   // candidates exhausted - ping the current one again
        }

        if (!beginPing()) return;   // a manual ping still holds the connection - skip the tick
        String[] body = new String[1];
        int code = -1;
        String switched = null;     // Claude address we have just moved to
        boolean aborted = false;
        try {
            code = probePing(probeUrl, body);
            aborted = pingAborted;  // aborted by a user request - this is not "server is silent"
            if (isFull && !aborted && myEpoch == reqEpoch && active) {
                fullDown = (code != 200);   // any responding Claude address = ping often again
                if (searching) {
                    if (code == 200) {
                        fullSearchPos = -1;
                        if (adoptFullUrl(probeUrl, target)) switched = probeUrl;
                    }
                    // no answer - next candidate on the next tick
                } else if (code == 200) {
                    fullFails = 0;
                } else if (++fullFails >= FULL_FAILS_TO_SWITCH) {
                    // the current Claude address failed twice in a row - from the next tick
                    // look for a live one. Counter to zero: the next search comes after two more failures
                    fullFails = 0;
                    fullSearchPos = 0;
                }
            }
        } finally {
            endPing();
        }
        // cancel/new request during the ping - the result is stale, do not touch the screen
        if (aborted || !active || myEpoch != reqEpoch) return;
        boolean ok = searching ? (switched != null) : (code == 200);
        String json = body[0] != null ? body[0] : "";
        String answered = switched != null ? switched : target;

        home.setOnline(askTg, ok);                    // dots at the bottom of the home screen
        if (target.equals(secU))                      // dot in the section header
            terminal.setServerOnline(ok);
        if (ok && answered.equals(serverUrl)) {
            String m = extractField(json, "mode");
            if (m != null) serverMode = m;
        }
        if (ok) applyServerFeatures(json);
        if (switched != null) noteFullSwitch(switched);
        if (askTg && ok) handleTgPing(json, myEpoch); // unread/preview - only from the TG role
    }

    /** Next candidate for the Claude search (other than the current dead one); null - exhausted. */
    private String nextFullCandidate(String dead) {
        String[] cands = fullCandidates();
        while (fullSearchPos >= 0 && fullSearchPos < cands.length) {
            String c = cands[fullSearchPos++];
            if (!c.equals(dead)) return c;
        }
        return null;
    }

    /** unread/preview from the TG role ping: home tile, vibration, a line in the chat. */
    private void handleTgPing(String json, int myEpoch) {
        // The worker may have started before the request was cancelled: no vibration
        // and no terminal lines for a stale result
        if (!active || myEpoch != reqEpoch) return;
        int u = extractInt(json, "unread");
        if (u < 0) return;
        int lid = extractInt(json, "last_id");
        String lf = extractField(json, "last_from");
        String lt = extractField(json, "last_text");
        String lc = extractField(json, "last_chat");
        if (u > 0 && lf != null) lastTgFrom = lf;
        if (u > 0 && lc != null) lastTgChat = lc;
        updateHomeTg(u, lf, lt);                       // tile - always fresh
        if (tgMode && lc != null && lc.equals(tgChatId) && lid > 0 && lid != lastShownTgId) {
            // new message in the OPEN chat - pull immediately, do not wait 15 s
            lastShownTgId = lid;
            loadTgMessages(false);
        } else if (!tgMode && lid > 0 && lid != lastShownTgId) {
            lastShownTgId = lid;
            try { display.vibrate(300); } catch (Exception ve) {}
            if (display.getCurrent() == terminal) {
                // in the Claude/OC chat - a preview line "+ name: ..."
                addWrappedText("+ " + (lf != null ? lf : "TG") + ": "
                    + (lt != null ? lt : ""), TerminalCanvas.COLOR_ACCENT);
                terminal.addLine("  " + Strings.tgReplyHint(), TerminalCanvas.COLOR_DIM);
                terminal.repaint();
            }
            // on the home screen the tile itself is the notification
        } else if (!tgMode && u > tgUnread && lid <= 0) {
            // old server without previews - previous behaviour
            try { display.vibrate(300); } catch (Exception ve) {}
            if (display.getCurrent() == terminal) {
                terminal.addLine("+ " + Strings.tgNewMessage(), TerminalCanvas.COLOR_ACCENT);
                terminal.repaint();
            }
        }
        tgUnread = u;
        terminal.setUnread(tgMode ? 0 : u);
    }

    private void initAudioPlayer() {
        try {
            if (audioPlayer != null) return;
            try {
                audioPlayer = Manager.createPlayer("capture://audio?encoding=amr");
            } catch (Exception e1) {
                try {
                    audioPlayer = Manager.createPlayer("capture://audio?encoding=audio/amr");
                } catch (Exception e2) {
                    audioPlayer = Manager.createPlayer("capture://audio");
                }
            }
            audioPlayer.realize();
            recordControl = (RecordControl) audioPlayer.getControl("RecordControl");
        } catch (Exception e) {}
    }

    private void closePlayer() {
        // Null it BEFORE close(): if close() throws, the broken Player would remain
        // and every next recording would fail with the same "Interrupted while waiting"
        Player p = audioPlayer;
        audioPlayer = null;
        recordControl = null;
        if (p != null) { try { p.close(); } catch (Exception e) {} }
    }

    private void buildCommands() {
        sendCmd = new Command(Strings.send(), Command.OK, 1);
        backCmd = new Command(Strings.back(), Command.BACK, 1);
        saveCmd = new Command(Strings.save(), Command.OK, 1);
        menuCmd = new Command("Menu", Command.SCREEN, 2);
        voiceSendCmd = new Command(Strings.send(), Command.OK, 1);
        voiceCancelCmd = new Command(Strings.cancel(), Command.BACK, 1);
        cancelRecordCmd = new Command(Strings.cancel(), Command.BACK, 1);
        stopCmd = new Command(Strings.stop(), Command.STOP, 1);
        // OK/FIRE on a list Canvas sends this command - like SELECT_COMMAND on a List
        openItemCmd = new Command(Strings.switchTo(), Command.OK, 1);
        cycleCmd = new Command(Strings.change(), Command.OK, 1);
    }

    private void initUI() {
        home = new HomeCanvas(this);
        homeOpenCmd = new Command(Strings.switchTo(), Command.OK, 1);
        homeExitCmd = new Command(Strings.exit(), Command.EXIT, 2);
        home.addCommand(homeOpenCmd);
        home.addCommand(homeExitCmd);
        home.setCommandListener(this);

        terminal = new TerminalCanvas(this);
        setNormalCommands();
        terminal.setCommandListener(this);

        // Text input is on a Canvas (multitap): keyRepeated arrives, a held 0
        // works as cancel even in the middle of typing
        inputCanvas = new InputCanvas(this, inputHistory);

        rebuildSettingsMenu();
    }

    private MenuCanvas settingsHub;

    private void showSettingsHub() {
        settingsHub = new MenuCanvas(this, Strings.settingsFull());
        settingsHub.append(Strings.params());
        settingsHub.append(Strings.features());
        if (Features.on(Features.ADVANCED)) {
            settingsHub.append(Strings.servers());
            settingsHub.append(Strings.update());
        }
        settingsHub.setSelectCommand(openItemCmd);
        settingsHub.addCommand(openItemCmd);
        settingsHub.addCommand(backCmd);
        settingsHub.setCommandListener(this);
        display.setCurrent(settingsHub);
    }

    // --- FEATURES (Settings > Features): what the simple menu hides ---

    private MenuCanvas featuresMenu;
    private int tmpFeat = 0;
    private int[] featBits;      // bit of each row (rows the server forbids are not listed)

    private void showFeaturesMenu() {
        tmpFeat = Features.local;
        featuresMenu = new MenuCanvas(this, Strings.features());
        int[] bits = { Features.TELEGRAM, Features.OPENCODE, Features.SESSIONS, Features.VOICE, Features.ADVANCED };
        featBits = bits;
        for (int i = 0; i < bits.length; i++) featuresMenu.append("");
        refreshFeatureRows();
        featuresMenu.setSelectCommand(cycleCmd);
        featuresMenu.addCommand(cycleCmd);
        featuresMenu.addCommand(saveCmd);
        featuresMenu.addCommand(backCmd);
        featuresMenu.setCommandListener(this);
        display.setCurrent(featuresMenu);
    }

    private void refreshFeatureRows() {
        for (int i = 0; i < featBits.length; i++) {
            int bit = featBits[i];
            String row;
            if ((Features.server & bit) == 0) row = "[-] " + Features.label(bit) + " (" + Strings.featServerOff() + ")";
            else row = ((tmpFeat & bit) != 0 ? "[x] " : "[ ] ") + Features.label(bit);
            featuresMenu.set(i, row);
        }
        featuresMenu.repaint();
    }

    private void toggleFeature(int row) {
        if (row < 0 || row >= featBits.length) return;
        int bit = featBits[row];
        if ((Features.server & bit) == 0) return;   // the server does not offer it
        tmpFeat ^= bit;
        refreshFeatureRows();
    }

    private void saveFeatures() {
        Features.local = tmpFeat & Features.ALL;
        persistFeatures();
        refreshHomeTiles();
        showSettingsHub();
    }

    /** The server's list of allowed features (the "features" field of /ping); absent = all allowed. */
    private void applyServerFeatures(String json) {
        String f = extractField(json, "features");
        if (f == null) return;
        int m = Features.parseServer(f);
        if (m != Features.server) {
            Features.server = m;
            refreshHomeTiles();
        }
    }

    private void loadFeatures() {
        boolean haveOldSettings = false;
        try {
            RecordStore o = RecordStore.openRecordStore("settings", false);
            o.closeRecordStore();
            haveOldSettings = true;
        } catch (Exception e) {}
        boolean found = false;
        try {
            RecordStore rs = RecordStore.openRecordStore("features", false);
            byte[] d = rs.getRecord(1);
            rs.closeRecordStore();
            Features.local = Integer.parseInt(new String(d, "UTF-8")) & Features.ALL;
            found = true;
        } catch (Exception e) {}
        if (!found) {
            // A fresh install gets the simple menu; an upgrade from a version that
            // had no Features keeps everything it already showed.
            Features.local = haveOldSettings ? Features.ALL : Features.DEFAULT;
            persistFeatures();
        }
    }

    private void persistFeatures() {
        try {
            byte[] data = String.valueOf(Features.local).getBytes("UTF-8");
            try {
                RecordStore rs = RecordStore.openRecordStore("features", false);
                rs.setRecord(1, data, 0, data.length);
                rs.closeRecordStore();
            } catch (RecordStoreNotFoundException e) {
                RecordStore rs = RecordStore.openRecordStore("features", true);
                rs.addRecord(data, 0, data.length);
                rs.closeRecordStore();
            }
        } catch (Exception e) {}
    }

    /** Settings: a list on a Canvas - OK cycles the row value, "Save" applies. */
    private void rebuildSettingsMenu() {
        tmpLang = Strings.getLang();
        tmpVoice = voiceMode;
        tmpFont = fontSize;
        settingsMenu = new MenuCanvas(this, Strings.settings());
        settingsMenu.append("");
        settingsMenu.append("");
        settingsMenu.append("");
        refreshSettingsRows();
        settingsMenu.setSelectCommand(cycleCmd);
        settingsMenu.addCommand(cycleCmd);
        settingsMenu.addCommand(saveCmd);
        settingsMenu.addCommand(backCmd);
        settingsMenu.setCommandListener(this);
    }

    /** Settings row labels from the draft. */
    private void refreshSettingsRows() {
        settingsMenu.set(0, Strings.language() + " " + (tmpLang == 0 ? "Русский" : "English"));
        settingsMenu.set(1, Strings.voiceMode() + " "
            + (tmpVoice == 0 ? Strings.voiceHoldStar() : Strings.voiceOkButton()));
        settingsMenu.set(2, Strings.fontSize() + " "
            + (tmpFont == 0 ? Strings.fontSmall() : Strings.fontMedium()));
    }

    /** OK on a settings row - toggle the value (in the draft only for now). */
    private void cycleSetting(int row) {
        if (row == 0) tmpLang = 1 - tmpLang;
        else if (row == 1) tmpVoice = 1 - tmpVoice;
        else if (row == 2) tmpFont = 1 - tmpFont;
        else return;
        refreshSettingsRows();
        settingsMenu.repaint();
    }


    /** Right side of the header - active server name: always visible where requests go. */
    /**
     * Address of the "Telegram" role: by the localized Telegram server name, then by port :8085.
     * No slot - EMPTY string (not serverUrls[0]: otherwise the primary Claude would become
     * the "TG role" and there would be no way back to it). Callers check length().
     */
    private String tgServerUrl() { return roleUrl(true); }
    /**
     * Address of the "Claude/OpenCode" role: the CURRENT LIVE one (after auto-probing or manual
     * choice) if it is still in the server list; otherwise the primary - by name,
     * then by port :8086, then the last one.
     */
    private String fullServerUrl() {
        String l = liveFullUrl;
        if (l != null && inServerList(l) && !isTgRoleUrl(l)) return l;
        return roleUrl(false);
    }

    private boolean inServerList(String u) {
        for (int i = 0; i < serverUrls.length; i++)
            if (serverUrls[i].equals(u)) return true;
        return false;
    }

    private static boolean hasPort(String u, String port) {
        return u.endsWith(port) || u.indexOf(port + "/") >= 0;
    }

    /** Address of the "Telegram" role (:8085 or the TG slot). Everything else is the "Claude" role. */
    private boolean isTgRoleUrl(String u) {
        if (u == null || u.length() == 0) return false;
        if (hasPort(u, ":#tg")) return true;
        String t = roleUrl(true);
        return t.length() > 0 && u.equals(t);
    }

    /** Claude role candidates for auto-probing: the primary first, then all :8086. */
    private String[] fullCandidates() {
        Vector v = new Vector();
        String p = roleUrl(false);
        if (!isTgRoleUrl(p)) v.addElement(p);
        for (int i = 0; i < serverUrls.length; i++) {
            String u = serverUrls[i];
            if (hasPort(u, ":#full") && !v.contains(u)) v.addElement(u);
        }
        String[] out = new String[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = (String) v.elementAt(i);
        return out;
    }

    /**
     * The current Claude address does not answer: try the other candidates in order
     * (the primary first - so a return to the primary happens only when the
     * current one died). The first to answer 200 becomes the live one. Call ONLY under
     * beginPing(). The winner's response body goes to body[0].
     * Returns the new address or null (nobody answered / aborted).
     */
    private String failoverFull(String dead, int myEpoch, String[] body) {
        String[] cands = fullCandidates();
        for (int i = 0; i < cands.length; i++) {
            String c = cands[i];
            if (c.equals(dead)) continue;
            // cancel, a new request or the user started typing - do not hold the air
            if (!active || myEpoch != reqEpoch || isLoading || pingAborted) return null;
            String[] b = new String[1];
            if (probePing(c, b) == 200) {
                if (!active || myEpoch != reqEpoch) return null;
                if (adoptFullUrl(c, dead)) { body[0] = b[0]; return c; }
                return null;   // the address was changed manually in the meantime - do not argue
            }
        }
        return null;
    }

    /**
     * Make url the live Claude address. expectedOld != null - only if the current one
     * is still expectedOld (do not overwrite a manual choice made during the ping).
     * Touch serverUrl ONLY if it is currently in the Claude role: the TG section has its own address.
     */
    private synchronized boolean adoptFullUrl(String url, String expectedOld) {
        if (url == null || isTgRoleUrl(url)) return false;
        if (expectedOld != null && !expectedOld.equals(fullServerUrl())) return false;
        fullFails = 0;
        fullDown = false;
        if (url.equals(liveFullUrl)) return true;
        liveFullUrl = url;
        fullSearchPos = -1;
        if (!isTgRoleUrl(serverUrl)) serverUrl = url;
        // the ">" marker in the server list follows the live address if the active slot is in the Claude role
        if (serverIdx < 0 || serverIdx >= serverUrls.length || !isTgRoleUrl(serverUrls[serverIdx])) {
            for (int i = 0; i < serverUrls.length; i++)
                if (serverUrls[i].equals(url)) { serverIdx = i; persistServers(); break; }
        }
        persistNetState();
        return true;
    }

    /** Claude auto-switch: a line in the open Claude/OpenCode chat (not in TG, and only while the app is running). */
    private void noteFullSwitch(String url) {
        if (!active || tgMode || display.getCurrent() != terminal) return;
        if (chatOwner != PANEL_CLAUDE && chatOwner != PANEL_OC) return;
        String name = url;
        for (int i = 0; i < serverUrls.length; i++)
            if (serverUrls[i].equals(url)) { name = serverNames[i]; break; }
        terminal.addLine("  " + Strings.serverSwitched() + " " + name, TerminalCanvas.COLOR_DIM);
        terminal.repaint();
    }

    /** One ping at a time: false - another ping still holds the only connection. */
    private boolean beginPing() {
        synchronized (pingLock) {
            if (pingBusy) return false;
            pingBusy = true;
            pingAborted = false;
            return true;
        }
    }

    /** A user request outranks a background ping: abort its connection. */
    private void preemptPing() {
        final HttpConnection pc = pingConn;
        if (pc != null) {
            pingAborted = true;   // BEFORE close: probePing returns -1 and is not counted
            pingConn = null;
            // close() on J9 BLOCKS (see onStopRequest) - it may wait for the ping to finish.
            // Close in a throwaway thread so the user request does not stand behind it
            new Thread(new Runnable() {
                public void run() { try { pc.close(); } catch (Exception e) {} }
            }).start();
        }
    }

    /** Abort the ping and wait briefly (up to 1 s) until it releases the connection. */
    private void yieldPing() {
        for (int i = 0; i < 10 && pingBusy; i++) {
            preemptPing();
            try { Thread.sleep(100); } catch (Exception ie) { break; }
        }
    }

    private void endPing() {
        synchronized (pingLock) { pingBusy = false; }
    }

    /**
     * GET /ping. Returns the HTTP code (-1 = no connection); the body on 200 goes to out[0].
     * Only 200 means a live server: 401/403 (key rejected) do not count as live.
     * The connection is always closed.
     */
    private int probePing(String base, String[] out) {
        HttpConnection conn = null;
        InputStream is = null;
        try {
            conn = openSignedRaw(base + "/ping", "GET", null);
            pingConn = conn;
            if (pingAborted) return -1;   // a user request arrived while opening
            int code = conn.getResponseCode();
            if (code == 200) {
                is = conn.openInputStream();
                out[0] = readUTF8(is);
            }
            return code;
        } catch (Exception e) {
            return -1;
        } finally {
            pingConn = null;
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (conn != null) try { conn.close(); } catch (Exception e) {}
        }
    }

    // --- "netstate" RMS: live Claude address + one-time migration flag ---
    // Format: "2|<backupMigrated 0/1>|<liveFullUrl or empty>" ("1|" is from 2.6.2, its address is not trusted)

    private void loadNetState() {
        try {
            RecordStore rs = RecordStore.openRecordStore("netstate", false);
            byte[] data = rs.getRecord(1);
            rs.closeRecordStore();
            String s = new String(data, "UTF-8");
            int p1 = s.indexOf('|');
            if (p1 < 0) return;
            int p2 = s.indexOf('|', p1 + 1);
            if (p2 < 0) return;
            backupMigrated = "1".equals(s.substring(p1 + 1, p2));
            // Do NOT take the address written by 2.6.2 (format "1|"): that version probed
            // servers blindly and could "stick" to the backup. Start from the primary,
            // then auto-probing finds a live one itself. Our own records (format "2|") are taken.
            if (s.startsWith("2|")) {
                String u = s.substring(p2 + 1);
                liveFullUrl = u.length() > 0 ? u : null;
            }
        } catch (Exception e) {
            // first launch of this version - live address unknown, migration not done
        }
    }

    private synchronized void persistNetState() {
        try {
            String l = liveFullUrl;
            String s = "2|" + (backupMigrated ? "1" : "0") + "|" + (l != null ? l : "");
            byte[] data = s.getBytes("UTF-8");
            try {
                RecordStore rs = RecordStore.openRecordStore("netstate", false);
                rs.setRecord(1, data, 0, data.length);
                rs.closeRecordStore();
            } catch (RecordStoreNotFoundException e) {
                RecordStore rs = RecordStore.openRecordStore("netstate", true);
                rs.addRecord(data, 0, data.length);
                rs.closeRecordStore();
            }
        } catch (Exception e) {}
    }

    private String roleUrl(boolean tg) {
        String byName = tg ? Strings.telegram() : "Клод";
        for (int i = 0; i < serverNames.length; i++)
            if (serverNames[i].equals(byName) || (tg && serverNames[i].equals("Telegram"))
                || (!tg && serverNames[i].equals("Claude")))
                return serverUrls[i];
        String port = tg ? ":#tg" : ":#full";
        for (int i = 0; i < serverUrls.length; i++)
            if (serverUrls[i].endsWith(port) || serverUrls[i].indexOf(port + "/") >= 0)
                return serverUrls[i];
        return tg ? "" : serverUrls[serverUrls.length - 1];   // no TG slot - empty
    }

    /** Opening a tile from the home screen. */
    public void onHomeOpen(int tile) {
        if (isLoading || !home.hasTile(tile)) return;
        switch (tile) {
            case HomeCanvas.TILE_TG:       openTelegramPanel(); break;
            case HomeCanvas.TILE_CLAUDE:   openChatPanel(PANEL_CLAUDE); break;
            case HomeCanvas.TILE_OC:       openChatPanel(PANEL_OC); break;
            case HomeCanvas.TILE_SESSIONS: openSessionsPanel(); break;
            case HomeCanvas.TILE_SETTINGS: showSettingsHub(); break;
        }
    }

    private void openTelegramPanel() {
        String tgU = tgServerUrl();
        if (tgU.length() == 0) {         // no TG slot (:8085) in the server list
            home.showOverlay(Strings.telegram(), new String[] {
                Strings.getLang() == 0 ? "Нет сервера Телеграм" : "No Telegram server",
                Strings.getLang() == 0 ? "Настройки - Серверы" : "Settings - Servers" });
            return;
        }
        activePanel = PANEL_TG;
        serverUrl = tgU;                 // auto-bind the role, WITHOUT persist
        serverMode = "tg";
        loadTgContacts();
    }

    private void openChatPanel(int panel) {
        activePanel = panel;
        serverUrl = fullServerUrl();     // the CURRENT live Claude address (after auto-probing), not the primary
        serverMode = "full";
        // leave the TG state quietly if it happens to be active
        tgMode = false; tgChatId = ""; tgTitle = "";
        String want = (panel == PANEL_OC) ? ocModel : claudeModel;
        String sid  = (panel == PANEL_OC) ? ocSessionId : claudeSessionId;
        boolean fresh = (chatOwner != panel);
        currentModel = want;
        sessionId = sid;
        if (fresh) {
            chatOwner = panel;
            terminal.clear();
            terminal.addLine("", TerminalCanvas.COLOR_DIM);
            terminal.addLine(" " + Strings.welcome1(), TerminalCanvas.COLOR_DIM);
            if (Features.on(Features.VOICE))
                terminal.addLine(" " + Strings.welcome2(), TerminalCanvas.COLOR_DIM);
            terminal.addLine(" " + Strings.welcome3(), TerminalCanvas.COLOR_DIM);
            terminal.addLine(" " + Strings.help6(), TerminalCanvas.COLOR_DIM);
            terminal.addLine("", TerminalCanvas.COLOR_DIM);
        }
        updateHeader();
        display.setCurrent(terminal);
        terminal.repaint();
    }

    private void openSessionsPanel() {
        serverUrl = fullServerUrl();
        serverMode = "full";
        loadSessions();
    }

    /** Home from anywhere. The terminal is NOT cleared - the chat lives until the panel changes. */
    public void onGoHome() {
        if (isLoading) return;
        goHome();
    }

    private void goHome() {
        activePanel = PANEL_NONE;
        if (tgMode) {                    // quiet exit from TG mode, no greetings
            tgMode = false; tgChatId = ""; tgTitle = "";
            chatOwner = PANEL_NONE;      // the TG chat in the terminal is stale
            lastShownTgId = 0;           // the preview will honestly show again
        }
        refreshHomeTiles();
        display.setCurrent(home);
    }

    /** Tile labels from the current state. */
    private void refreshHomeTiles() {
        home.setTiles(Features.local & Features.server);
        home.setClaudeSub(labelFor(claudeModel));
        home.setOcSub(labelFor(ocModel));
        updateHomeTg(tgUnread, lastTgFrom, null);
    }

    private String labelFor(String modelId) {
        for (int i = 0; i < MODEL_IDS.length; i++)
            if (MODEL_IDS[i].equals(modelId)) return MODEL_NAMES[i];
        return modelId;
    }

    private void updateHomeTg(int u, String lf, String lt) {
        String sub;
        if (u > 0 && lf != null && lf.length() > 0)
            sub = lf + ": " + (lt != null ? lt : "");
        else if (u > 0) sub = Strings.tgNewMessage();
        else sub = tgBotName.length() > 0 ? "@" + tgBotName : Strings.noNews();
        home.setTgState(u, sub);
    }

    public void onHomeHelp() {
        home.showOverlay(Strings.helpTitle(), new String[] {
            Strings.homeHelp1(), Strings.homeHelp2(), Strings.homeHelp3(),
            Strings.homeHelp4(), Strings.help5() });
    }

    public void onHomeAbout() {
        home.showOverlay("VibeNokia v" + APP_VERSION, new String[] {
            "TG: " + tgServerUrl(),
            Strings.claudeTile() + ": " + fullServerUrl(),
            Strings.model() + " " + labelFor(claudeModel),
            "OpenCode: " + labelFor(ocModel),
            tgBotName.length() > 0 ? "@" + tgBotName : "" });
    }

    public void onHomePingNow() { pingFlip = 0; }

    /** First screen after launch: the home menu, then pairing (once) or the network start. */
    private void startSession() {
        goHome();
        refreshHomeTiles();
        // Pairing is asked ONLY while the phone has no key. Once it appears -
        // never again: it lives in phone memory and survives updates.
        if (!DeviceAuth.isPaired()) {
            // The network is not woken at all: without a key every request is rejected anyway,
            // and the splash is removed immediately - otherwise the home screen would stay under the splash
            home.hideSplash();
            showPairing(false);
            return;
        }
        bootNetwork();
    }

    /** First network start at startup (or right after pairing). */
    private void bootNetwork() {
        if (netBooted) {   // already woke up before - just restore the ping
            if (pingTimer == null) startPingTimer();
            return;
        }
        netBooted = true;
        // Normal start: sound (permission is asked once), splash, ping, models
        new Thread(new Runnable() {
            public void run() {
                final int myEpoch = reqEpoch;   // cancel during boot = reqEpoch grows
                try {
                    if (Features.on(Features.VOICE)) initAudioPlayer();   // mic permission only if voice is on
                } catch (Exception e) {}
                try { Thread.sleep(1500); } catch (Exception e) {}
                if (myEpoch != reqEpoch) return;   // cancel in the meantime - do not wake the network
                home.hideSplash();
                refreshHomeTiles();
                // Start STRICTLY in order: ping -> /api/models -> /api/update, and only
                // then the ping timer. Simultaneous requests to one backend arrived
                // with X-Cnt numbers out of order -> the server rejected them as a replay
                // (user actions do NOT abort the queue - only cancel does; the timer
                // always starts, otherwise the ping would not come alive until the next unlock)
                try { pingTick(); } catch (Exception e) {}
                // Both requests go to Claude: the user is already in the TG section - do not occupy the channel
                // (models are refreshed anyway when the model picker opens)
                if (active && activePanel != PANEL_TG) loadModels(false, true);
                if (active && activePanel != PANEL_TG) checkUpdateSilent(true);
                if (active && pingTimer == null) startPingTimer(5000);
            }
        }).start();
    }

    // --- PHONE PAIRING (once per installation) ---

    /** Pairing screen: explains what to do and waits for "5". */
    private void showPairing(boolean again) {
        display.setCurrent(terminal);
        terminal.clear();
        terminal.addLine("", TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + (again ? Strings.pairLost() : Strings.pairTitle()),
                         TerminalCanvas.COLOR_ACCENT);
        terminal.addLine("", TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.pairStep1(), TerminalCanvas.COLOR_TEXT);
        terminal.addLine(" " + Strings.pairStep2(), TerminalCanvas.COLOR_TEXT);
        terminal.addLine(" " + Strings.pairStep3(), TerminalCanvas.COLOR_DIM);
        terminal.repaint();
        pairing = true;
    }

    private boolean pairing = false;

    /** Key 5 on the pairing screen - ask the server to issue a code. */
    private void pairRequestCode() {
        if (isLoading) return;
        isLoading = true;
        terminal.startSpinner(Strings.sending());
        final int myEpoch = ++reqEpoch;
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    doPostPlain(pairServerUrl() + "/pair/start", "{}");
                } catch (Exception e) {
                    err = httpError(e);
                }
                if (myEpoch != reqEpoch || !active) return;
                terminal.stopSpinner();
                isLoading = false;
                if (err != null) {
                    terminal.addLine(" " + err, TerminalCanvas.COLOR_ERROR);
                    terminal.repaint();
                    return;
                }
                terminal.addLine(" " + Strings.pairCodeSent(), TerminalCanvas.COLOR_ACCENT);
                terminal.repaint();
                inputPurpose = IP_PAIR;
                inputCanvas.openNumeric(Strings.pairCode(), "", 6);
                display.setCurrent(inputCanvas);
            }
        }).start();
    }

    /** Code entered: obtain the phone's own key and remember it permanently. */
    private void pairConfirm(final String code) {
        display.setCurrent(terminal);
        isLoading = true;
        terminal.startSpinner(Strings.sending());
        final int myEpoch = ++reqEpoch;
        new Thread(new Runnable() {
            public void run() {
                String err = null, id = null, key = null;
                try {
                    String res = doPostPlain(pairServerUrl() + "/pair/confirm",
                        "{\"code\":\"" + escapeJson(code) + "\",\"name\":\"nokia\"}");
                    id = extractField(res, "device_id");
                    key = extractField(res, "secret");
                } catch (Exception e) {
                    err = httpError(e);
                }
                if (myEpoch != reqEpoch || !active) return;
                terminal.stopSpinner();
                isLoading = false;
                if (id == null || key == null) {
                    terminal.addLine(" " + (err != null ? err : Strings.pairBadCode()),
                                     TerminalCanvas.COLOR_ERROR);
                    terminal.addLine(" " + Strings.pairStep1(), TerminalCanvas.COLOR_DIM);
                    terminal.repaint();
                    return;
                }
                DeviceAuth.setPaired(id, key);
                pairing = false;
                terminal.addLine(" " + Strings.pairDone(), TerminalCanvas.COLOR_ACCENT);
                terminal.repaint();
                goHome();
                bootNetwork();   // the same start as usual: ping, models, sound
            }
        }).start();
    }

    /** Reissue the phone key: the old one lives until the new one works. */
    private void rotateKey() {
        if (isLoading || !DeviceAuth.isPaired()) return;
        isLoading = true;
        display.setCurrent(terminal);
        terminal.startSpinner(Strings.sending());
        final int myEpoch = ++reqEpoch;
        new Thread(new Runnable() {
            public void run() {
                String key = null, err = null;
                boolean secondOk = true;
                try {
                    String res = doPost(pairServerUrl() + "/device/rotate", "{}");
                    key = extractField(res, "secret");
                    // There are two servers, each with its own database: the second must accept the same
                    // key while the OLD one is still valid - otherwise the phone would lose it.
                    String other = otherServerUrl();
                    if (key != null && other.length() > 0 && !other.equals(pairServerUrl())) {
                        try {
                            doPost(other + "/device/adopt",
                                   "{\"secret\":\"" + escapeJson(key) + "\"}");
                        } catch (Exception e2) {
                            secondOk = false;
                        }
                    }
                } catch (Exception e) {
                    err = httpError(e);
                }
                if (myEpoch != reqEpoch || !active) return;
                terminal.stopSpinner();
                isLoading = false;
                if (key == null) {
                    terminal.addLine(" " + (err != null ? err : Strings.pairBadCode()),
                                     TerminalCanvas.COLOR_ERROR);
                } else {
                    DeviceAuth.setSecret(key);
                    terminal.addLine(" " + Strings.keyRotated(), TerminalCanvas.COLOR_ACCENT);
                    if (!secondOk)
                        terminal.addLine(" " + Strings.keySecondFailed(), TerminalCanvas.COLOR_ERROR);
                }
                terminal.repaint();
            }
        }).start();
    }

    /** Pairing goes to the public server: it is always reachable, unlike the Claude channel. */
    private String pairServerUrl() {
        String t = tgServerUrl();
        return t.length() > 0 ? t : fullServerUrl();   // no TG slot - pair through Claude
    }

    /** Second server (Claude): it also needs a new key on reissue. */
    private String otherServerUrl() { return fullServerUrl(); }

    /** POST without a signature - only for pairing, when the phone has no key yet. */
    private String doPostPlain(String endpoint, String jsonBody) throws IOException {
        HttpConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            conn = (HttpConnection) Connector.open(endpoint);
            conn.setRequestMethod(HttpConnection.POST);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            byte[] body = jsonBody.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            os = conn.openOutputStream();
            os.write(body);
            os.flush();
            int code = conn.getResponseCode();
            is = conn.openInputStream();
            String result = readUTF8(is);
            if (code != 200) throw new IOException("HTTP " + code);
            return result;
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (os != null) try { os.close(); } catch (Exception e) {}
            if (conn != null) try { conn.close(); } catch (Exception e) {}
        }
    }

    /** Single point of session change - mirrors into the per-panel copy. */
    private void setSession(String sid) {
        sessionId = sid;
        if (chatOwner == PANEL_OC) ocSessionId = sid; else claudeSessionId = sid;
    }

    private void updateHeader() {
        // Context header: server (or TG: name) on the left, a SHORT model tag on the right
        // (<=7 characters - never truncated into mush)
        if (tgMode) {
            terminal.setTitle("TG: " + tgTitle);
            terminal.setModelName("");
            return;
        }
        // The title comes from the PANEL, not from the server list slot: auto-binding of
        // servers would break "serverIdx == active" (it showed "Telegram" in the Claude section)
        terminal.setTitle(activePanel == PANEL_OC ? "OpenCode" : Strings.claudeTile());
        terminal.setModelName(shortModelTag(getModelDisplayName()));
    }

    /** "free DeepSeek"->"DeepSek", "Kimi K3 $$$"->"KimiK3$", "MiMo2.5 c"->"MiM2.5c" */
    private String shortModelTag(String label) {
        String marker = "";
        int sp = label.lastIndexOf(' ');
        if (sp > 0) {
            String last = label.substring(sp + 1);
            boolean dollars = last.length() > 0;
            for (int i = 0; i < last.length(); i++) if (last.charAt(i) != '$') { dollars = false; break; }
            if (dollars) { marker = "$"; label = label.substring(0, sp); }
            else if (last.equals("c")) { marker = "c"; label = label.substring(0, sp); }
        }
        if (label.startsWith("free ")) label = label.substring(5);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < label.length(); i++)
            if (label.charAt(i) != ' ') sb.append(label.charAt(i));
        String s = sb.toString();
        int limit = 7 - marker.length();
        while (s.length() > limit) {
            int cut = -1;
            for (int i = s.length() - 1; i > 0; i--) {
                char ch = s.charAt(i);
                if (ch=='a'||ch=='e'||ch=='i'||ch=='o'||ch=='u') { cut = i; break; }
            }
            if (cut < 0) break;
            s = s.substring(0, cut) + s.substring(cut + 1);
        }
        if (s.length() > limit) s = s.substring(0, limit);
        while (s.length() > 0) {
            char e = s.charAt(s.length() - 1);
            if (e=='.'||e=='-'||e=='+') s = s.substring(0, s.length() - 1); else break;
        }
        return s + marker;
    }

    private String getModelDisplayName() {
        for (int i = 0; i < MODEL_IDS.length; i++) {
            if (MODEL_IDS[i].equals(currentModel)) return MODEL_NAMES[i];
        }
        return currentModel;
    }

    /** Quiet version check at startup: newer -> one line, no browser. */
    private void checkUpdateSilent(boolean sync) {
        if (!active) return;
        final int myEpoch = reqEpoch;
        Runnable job = new Runnable() {
            public void run() {
                if (myEpoch != reqEpoch || !active) return;   // cancel between start and thread
                try {
                    HttpConnection conn = openSigned(fullServerUrl() + "/api/update", "GET", null);
                    int code = conn.getResponseCode();
                    String json = "";
                    if (code == 200) {
                        InputStream is = conn.openInputStream();
                        json = readUTF8(is);
                        is.close();
                    }
                    conn.close();
                    String v = extractField(json, "version");
                    if (myEpoch != reqEpoch || !active) return;   // the app is stopping: draw nothing
                    if (v != null && v.length() > 0 && !v.equals(APP_VERSION)) {
                        home.setUpdateHint("+ v" + v);
                    }
                } catch (Exception e) {}
            }
        };
        if (sync) job.run(); else new Thread(job).start();   // sync: at startup, requests run in sequence
    }

    /** Refresh the model list from the server (/api/models). Quiet: on failure the
     * previous one stays. menu=true - the model picker is open: when a list arrives, rebuild the menu. */
    private void loadModels(final boolean menu, boolean sync) {
        if (!active) return;
        final int myEpoch = reqEpoch;
        Runnable job = new Runnable() {
            public void run() {
                if (myEpoch != reqEpoch || !active) return;   // cancel between start and thread
                try {
                    HttpConnection conn = openSigned(fullServerUrl() + "/api/models", "GET", null);
                    int code = conn.getResponseCode();
                    if (code != 200) { conn.close(); return; }
                    InputStream is = conn.openInputStream();
                    String json = readUTF8(is);
                    is.close();
                    conn.close();
                    java.util.Vector ids = new java.util.Vector();
                    java.util.Vector labels = new java.util.Vector();
                    int sf = 0;
                    while (true) {
                        int i1 = json.indexOf("\"id\":\"", sf);
                        if (i1 < 0) break;
                        i1 += 6;
                        int i2 = json.indexOf("\"", i1);
                        if (i2 < 0) break;
                        int l1 = json.indexOf("\"label\":\"", i2);
                        if (l1 < 0) break;
                        l1 += 9;
                        int l2 = json.indexOf("\"", l1);
                        if (l2 < 0) break;
                        ids.addElement(json.substring(i1, i2));
                        labels.addElement(unescapeJson(json.substring(l1, l2)));
                        sf = l2 + 1;
                    }
                    if (myEpoch != reqEpoch || !active || ids.size() == 0) return;
                    final String[] nid = new String[ids.size()];
                    final String[] nlb = new String[ids.size()];
                    for (int i = 0; i < ids.size(); i++) {
                        nid[i] = (String) ids.elementAt(i);
                        nlb[i] = (String) labels.elementAt(i);
                    }
                    final String def = extractField(json, "oc_default");
                    // Apply on the UI thread: menu/header read the lists from there
                    display.callSerially(new Runnable() {
                        public void run() {
                            if (myEpoch != reqEpoch || !active) return;   // the app is stopping: do not touch the screen
                            applyModels(nid, nlb, def, menu);
                        }
                    });
                } catch (Exception e) {
                    // network unavailable - the previous list stays, not an error for the user
                }
            }
        };
        if (sync) job.run(); else new Thread(job).start();   // sync: at startup, requests run in sequence
    }

    private boolean hasModel(String[] ids, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return true;
        return false;
    }

    /** New list from the server. If the selected OpenCode model disappeared (paid, removed,
     * not working) - quietly switch to the server's default free one. */
    private void applyModels(String[] nid, String[] nlb, String def, boolean menu) {
        MODEL_IDS = nid;
        MODEL_NAMES = nlb;
        if (def != null && def.startsWith("opencode/") && hasModel(nid, def)) ocDefault = def;
        String fallback = hasModel(nid, ocDefault) ? ocDefault : null;
        if (fallback == null)
            for (int i = 0; i < nid.length; i++)
                if (nid[i].startsWith("opencode")) { fallback = nid[i]; break; }
        if (fallback != null) {
            boolean changed = false;
            if (!hasModel(nid, ocModel)) { ocModel = fallback; changed = true; }
            if (currentModel.startsWith("opencode") && !hasModel(nid, currentModel)) {
                currentModel = ocModel;
                changed = true;
            }
            if (changed) {
                persistSettings();
                refreshHomeTiles();
                if (display.getCurrent() == terminal) { updateHeader(); terminal.repaint(); }
            }
        }
        if (menu && modelsList != null && display.getCurrent() == modelsList && !isLoading && !tgMode) {
            int r = modelsList.getSelectedIndex();
            showModelMenu(pickIds != null && r >= 0 && r < pickIds.length ? pickIds[r] : null);
        }
    }

    // --- RMS SETTINGS ---

    private void loadSettings() {
        try {
            RecordStore rs = RecordStore.openRecordStore("settings", false);
            byte[] data = rs.getRecord(1);
            rs.closeRecordStore();
            String s = new String(data, "UTF-8");
            // Format: serverUrl|model|lang|voiceMode
            int p1 = s.indexOf('|');
            if (p1 < 0) return;
            int p2 = s.indexOf('|', p1 + 1);
            if (p2 < 0) return;
            int p3 = s.indexOf('|', p2 + 1);
            if (p3 < 0) return;
            serverUrl = s.substring(0, p1);
            currentModel = s.substring(p1 + 1, p2);
            int lang = Integer.parseInt(s.substring(p2 + 1, p3));
            Strings.setLang(lang);
            int p4 = s.indexOf('|', p3 + 1);
            if (p4 < 0) {
                voiceMode = Integer.parseInt(s.substring(p3 + 1));
            } else {
                voiceMode = Integer.parseInt(s.substring(p3 + 1, p4));
                int p5 = s.indexOf('|', p4 + 1);
                if (p5 < 0) {
                    try { fontSize = Integer.parseInt(s.substring(p4 + 1)); } catch (Exception fe) {}
                } else {
                    try { fontSize = Integer.parseInt(s.substring(p4 + 1, p5)); } catch (Exception fe) {}
                    int p6 = s.indexOf('|', p5 + 1);
                    if (p6 >= 0) {
                        String cm = s.substring(p5 + 1, p6);
                        String om = s.substring(p6 + 1);
                        if (cm.length() > 0) claudeModel = cm;
                        if (om.length() > 0) ocModel = om;
                    }
                }
            }
            // upgrade from old versions: seed the per-panel models from the common one
            if (currentModel.startsWith("opencode")) ocModel = currentModel;
            else if (currentModel.length() > 0) claudeModel = currentModel;
        } catch (Exception e) {
            // No settings saved yet or parse error, use defaults
        }
        // Upgrade from 2.6.1-: memory may hold a paid model (opencode-go/...) or a
        // removed free one. Until /api/models answers OpenCode would not work with it -
        // take the default free one (the server will fix the list itself later)
        if (!hasModel(MODEL_IDS, ocModel)) ocModel = ocDefault;
        if (currentModel.startsWith("opencode") && !hasModel(MODEL_IDS, currentModel)) currentModel = ocModel;
    }

    private void persistSettings() {
        try {
            String s = serverUrl + "|" + currentModel + "|" + Strings.getLang() + "|" + voiceMode
                 + "|" + fontSize + "|" + claudeModel + "|" + ocModel;
            byte[] data = s.getBytes("UTF-8");
            try {
                RecordStore rs = RecordStore.openRecordStore("settings", false);
                rs.setRecord(1, data, 0, data.length);
                rs.closeRecordStore();
            } catch (RecordStoreNotFoundException e) {
                RecordStore rs = RecordStore.openRecordStore("settings", true);
                rs.addRecord(data, 0, data.length);
                rs.closeRecordStore();
            }
        } catch (Exception e) {}
    }

    // --- COMMANDS ---

    private void setNormalCommands() {
        removeAllCommands(terminal);
        terminal.addCommand(sendCmd);
        terminal.addCommand(menuCmd);
    }

    // While a request runs, the only button is "Stop" (plus the # hint on screen).
    private void setLoadingCommands() {
        removeAllCommands(terminal);
        terminal.addCommand(stopCmd);
    }

    private void setRecordingCommands() {
        removeAllCommands(terminal);
        terminal.addCommand(voiceSendCmd);    // "Send" = finish recording and send
        terminal.addCommand(cancelRecordCmd);
    }

    private void setVoiceReadyCommands() {
        removeAllCommands(terminal);
        terminal.addCommand(voiceSendCmd);
        terminal.addCommand(voiceCancelCmd);
    }

    private void removeAllCommands(Displayable d) {
        d.removeCommand(sendCmd);
        d.removeCommand(menuCmd);
        d.removeCommand(voiceSendCmd);
        d.removeCommand(voiceCancelCmd);
        d.removeCommand(cancelRecordCmd);
        d.removeCommand(backCmd);
        d.removeCommand(saveCmd);
        d.removeCommand(stopCmd);
    }

    // --- CALLBACKS FROM CANVAS ---

    public void onVoiceStart() { startRecording(); }
    public void onVoiceStop() {
        // commit blocks - move it off the key thread
        new Thread(new Runnable() { public void run() { stopRecording(true); } }).start();
    }
    public void onVoiceSend() {
        new Thread(new Runnable() {
            public void run() {
                if (isRecording) stopRecording(true);  // commit finishes BEFORE the bytes are read
                sendVoiceMessage();                    // if commit failed, audioStream==null -> silent exit
            }
        }).start();
    }
    public boolean isRequestActive() { return isLoading; }

    public void onOkPressed() {
        if (isLoading) return;
        if (voiceMode == 1) startRecording();
    }

    /** Key 5: in a TG chat - pull the conversation; outside a chat - jump to the freshest unread one. */
    public void onRefreshChat() {
        if (pairing) { pairRequestCode(); return; }    // pairing screen: 5 - get a code
        if (isLoading) return;
        if (tgMode) {
            loadTgMessages(true);
        } else if (lastTgChat.length() > 0 && tgUnread > 0 && tgServerUrl().length() > 0) {
            // jump to the freshest unread chat - switching to the TG panel
            activePanel = PANEL_TG;
            serverUrl = tgServerUrl();
            serverMode = "tg";
            chatOwner = PANEL_TG;
            openTgChat(lastTgChat, lastTgFrom.length() > 0 ? lastTgFrom : "TG");
        } else {
            pingServerNow();   // no unread - connectivity check
        }
    }

    /** Key 7: clear the screen. */
    public void onClearScreen() {
        if (isLoading) return;
        terminal.clear();
        terminal.addLine(Strings.cleared(), TerminalCanvas.COLOR_DIM);
        terminal.repaint();
    }

    public void onStopRequest() {
        if (!isLoading) return;
        // IMPORTANT: closing a connection blocks the thread. onStopRequest is called from
        // the key/paint thread - so only cheap in-memory operations here,
        // and the closing itself and /api/stop go to a background thread, otherwise the screen freezes.
        reqEpoch++;                       // mark the current worker thread as stale
        isLoading = false;
        terminal.stopSpinner();
        terminal.addLine(Strings.stopped(), TerminalCanvas.COLOR_ACCENT);
        setNormalCommands();
        terminal.repaint();
        final HttpConnection dead = activeConn;
        activeConn = null;
        final boolean wasTg = tgMode;
        final String sidToStop = sessionId;
        new Thread(new Runnable() {
            public void run() {
                if (dead != null) { try { dead.close(); } catch (Exception e) {} }
                if (wasTg) return;  // nothing to stop on the server in Telegram mode
                try {
                    doPost(serverUrl + "/api/stop", "{\"session_id\":\"" + escapeJson(sidToStop) + "\"}");
                } catch (Exception e) {}
            }
        }).start();
    }

    // --- COMMAND HANDLER ---

    public void commandAction(Command c, Displayable d) {
        if (d == home) {
            if (c == homeOpenCmd) onHomeOpen(home.getSelected());
            else if (c == homeExitCmd) destroyApp(false);
            return;
        }
        if (d == settingsHub) {
            if (c == backCmd) { goHome(); return; }
            String it = settingsHub.getString(settingsHub.getSelectedIndex());
            if (it.equals(Strings.params())) { rebuildSettingsMenu(); display.setCurrent(settingsMenu); }
            else if (it.equals(Strings.features())) showFeaturesMenu();
            else if (it.equals(Strings.servers())) showServersList();
            else { display.setCurrent(terminal); checkForUpdate(); }
            return;
        }
        if (d == featuresMenu) {
            if (c == backCmd) { showSettingsHub(); return; }
            if (c == saveCmd) { saveFeatures(); return; }
            toggleFeature(featuresMenu.getSelectedIndex());
            return;
        }
        if (d == settingsMenu) {
            if (c == backCmd) { showSettingsHub(); return; }
            if (c == saveCmd) { saveSettings(); return; }
            cycleSetting(settingsMenu.getSelectedIndex());
            return;
        }
        if (c == stopCmd && d == terminal) {
            onStopRequest();
        } else if (c == sendCmd && d == terminal) {
            if (pairing) { onRefreshChat(); return; }   // on the pairing screen "Send" = get a code
            if (isLoading) return;
            openMessageInput();   // the draft is kept: left via "Back" - the text waits
        } else if (c == menuCmd && d == terminal) {
            if (!isLoading) showMenu();
        } else if (d == serversList) {
            if (c == backCmd) { showSettingsHub(); return; }
            handleServersAction(c);
        } else if (d == tgList) {
            if (c == backCmd) { goHome(); return; }
            handleTgListAction(c);
        } else if (d == modelsList) {
            if (c == backCmd) { display.setCurrent(terminal); return; }
            pickModel(modelsList.getSelectedIndex());
        } else if (c == backCmd && d == sessionsList) {
            goHome();
        } else if (c == backCmd) {
            display.setCurrent(terminal);
        } else if (c == voiceSendCmd && d == terminal) {
            sendVoiceMessage();
        } else if ((c == voiceCancelCmd || c == cancelRecordCmd) && d == terminal) {
            cancelVoice();
        } else if (d == menuList) {
            if (c == backCmd) { display.setCurrent(terminal); return; }
            handleMenuSelection(menuList.getSelectedIndex());
        } else if (d == sessionsList) {
            if (c == backCmd) { goHome(); return; }
            handleSessionAction(c);
        }
    }

    // --- TEXT INPUT (everything via InputCanvas, routed by inputPurpose) ---

    /** Open message input: Claude/OpenCode chat or TG - by the current mode. */
    private void openMessageInput() {
        inputPurpose = IP_CHAT;
        inputCanvas.open(tgMode ? "TG: " + tgTitle : "> ", chatDraft, 500);
        display.setCurrent(inputCanvas);
    }

    /** InputCanvas: Send/OK pressed. The step-by-step forms (server, contact) go through here too. */
    public void onInputDone(String text) {
        String msg = text.trim();
        if (inputPurpose == IP_CHAT) {
            if (msg.length() == 0) return;             // empty - stay in the input
            if (isLoading) {
                // do not lose typed text silently: the draft stays, the input closes
                chatDraft = text;
                display.setCurrent(terminal);
                terminal.addLine(Strings.getLang() == 0 ? "занято, повтори" : "busy, retry",
                                 TerminalCanvas.COLOR_ERROR);
                terminal.repaint();
                return;
            }
            chatDraft = "";
            rememberHistory(msg);
            display.setCurrent(terminal);
            if (tgMode) sendTgText(msg); else sendTextMessage(msg);
        } else if (inputPurpose == IP_SERVER_NAME) {
            pendingServerName = msg;
            inputPurpose = IP_SERVER_URL;              // step 2: address
            inputCanvas.open(Strings.server(),
                             editingServer >= 0 ? serverUrls[editingServer] : "http://", 200);
        } else if (inputPurpose == IP_SERVER_URL) {
            if (msg.length() < 8) return;              // address too short - stay
            finishServerEdit(msg);
        } else if (inputPurpose == IP_TG_ID) {
            if (msg.length() == 0) return;
            pendingTgId = msg;
            inputPurpose = IP_TG_NAME;                 // step 2: name
            inputCanvas.open(Strings.name(), "", 40);
        } else if (inputPurpose == IP_TG_NAME) {
            addTgContact(pendingTgId, msg);
        } else if (inputPurpose == IP_PAIR) {
            if (msg.length() < 4) return;              // code too short - keep waiting
            pairConfirm(msg);
        }
    }

    /** InputCanvas: Back pressed. The chat draft is kept, form steps go one level up. */
    public void onInputCancel() {
        if (inputPurpose == IP_CHAT) {
            chatDraft = inputCanvas.getText();
            display.setCurrent(terminal);
        } else if (inputPurpose == IP_SERVER_NAME || inputPurpose == IP_SERVER_URL) {
            showServersList();                         // back exactly one level up
        } else {
            if (tgList != null) display.setCurrent(tgList); else display.setCurrent(terminal);
        }
    }

    /** History of sent messages for up/down in the input: no consecutive duplicates, last 20 kept. */
    private void rememberHistory(String msg) {
        int n = inputHistory.size();
        if (n > 0 && msg.equals((String) inputHistory.elementAt(n - 1))) return;
        inputHistory.addElement(msg);
        while (inputHistory.size() > 20) inputHistory.removeElementAt(0);
    }

    // --- MENU ---

    private void showMenu() {
        menuList = new MenuCanvas(this, "Menu");
        // Sections live on the home screen - the chat menus are compact now
        if (tgMode) {
            menuList.append(Strings.refreshChat());
            menuList.append(Strings.contacts());
            menuList.append(Strings.home());
        } else {
            menuList.append(Strings.newChat());
            if (Features.on(Features.SESSIONS)) menuList.append(Strings.sessions());
            menuList.append(Strings.model());
            menuList.append(Strings.clear());
            menuList.append(Strings.help());
            if (Features.on(Features.ADVANCED)) {
                menuList.append(Strings.keyRotate());
                menuList.append(Strings.relink());
            }
            menuList.append(Strings.home());
            menuList.append(Strings.exit());
        }
        menuList.setSelectCommand(openItemCmd);
        menuList.addCommand(openItemCmd);
        menuList.addCommand(backCmd);
        menuList.setCommandListener(this);
        display.setCurrent(menuList);
    }

    private void handleMenuSelection(int idx) {
        // Dispatcher by item TEXT - one for both modes, indices do not drift apart
        String sel = menuList.getString(idx);
        if (sel.equals(Strings.refreshChat())) { display.setCurrent(terminal); loadTgMessages(true); }
        else if (sel.equals(Strings.contacts())) loadTgContacts();
        else if (sel.equals(Strings.home())) goHome();
        else if (sel.equals(Strings.newChat())) { display.setCurrent(terminal); createNewSession(); }
        else if (sel.equals(Strings.sessions())) loadSessions();
        else if (sel.equals(Strings.model())) onPickModel();
        else if (sel.equals(Strings.clear())) {
            display.setCurrent(terminal);
            terminal.clear();
            terminal.addLine(Strings.cleared(), TerminalCanvas.COLOR_DIM);
        }
        else if (sel.equals(Strings.help())) showHelp();
        else if (sel.equals(Strings.keyRotate())) rotateKey();
        else if (sel.equals(Strings.relink())) { DeviceAuth.forget(); showPairing(true); }
        else if (sel.equals(Strings.exit())) destroyApp(false);
    }

    /** Key help (key 9 and Menu -> Help). */
    private void showHelp() {
        display.setCurrent(terminal);
        terminal.addLine(Strings.helpTitle(), TerminalCanvas.COLOR_ACCENT);
        terminal.addLine(" " + Strings.help1(), TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.help2(), TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.help3(), TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.help4(), TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.help5(), TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.help6(), TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.help7(), TerminalCanvas.COLOR_DIM);
        terminal.addLine(" " + Strings.help8(), TerminalCanvas.COLOR_DIM);
        terminal.repaint();
    }

    public void onShowHelp() { showHelp(); }

    /** "About": version, server, model, bot - everything for quick diagnostics. */

    private void saveSettings() {
        // Models are on key 3 inside a chat panel; servers are in Settings->Servers.
        // Only language, voice and font remain here.
        voiceMode = tmpVoice;
        int newFont = tmpFont;
        if (newFont != fontSize) {
            fontSize = newFont;
            terminal.setFontSize(fontSize);
        }
        int newLang = tmpLang;
        if (newLang != Strings.getLang()) {
            tgMode = false; tgChatId = ""; tgTitle = "";
            chatOwner = PANEL_NONE;   // chat content is in the old language - reset
            Strings.setLang(newLang);
            buildCommands();
            initUI();
            if (fontSize != 0) terminal.setFontSize(fontSize);
            home.hideSplash();
            refreshHomeTiles();
            goHome();
            persistSettings();
            return;
        }
        updateHeader();
        persistSettings();
        showSettingsHub();
    }

    // --- SESSIONS ---

    private void createNewSession() {
        if (isLoading) return;
        if ("tg".equals(serverMode)) {
            terminal.addLine(Strings.claudeOffHere(), TerminalCanvas.COLOR_ERROR);
            terminal.repaint();
            return;
        }
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        setSession("");
        terminal.clear();
        terminal.startSpinner(Strings.getLang() == 0 ? "создание..." : "creating...");

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String body = "{\"model\":\"" + escapeJson(currentModel) + "\"}";
                    String result = doPost(serverUrl + "/api/sessions/new", body);
                    String sid = extractField(result, "session_id");
                    if (sid != null) setSession(sid);
                } catch (Exception e) {
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    isLoading = false;
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                    terminal.repaint();
                    return;   // do not report "new session" if it was not created
                }
                if (myEpoch != reqEpoch) return;
                terminal.stopSpinner();
                terminal.addLine(Strings.newSessionStarted(), TerminalCanvas.COLOR_DIM);
                isLoading = false;
                terminal.repaint();
            }
        });
        t.start();
    }

    private void loadSessions() {
        // Guard: the session-delete worker calls loadSessions() AFTER cancel
        // (isLoading was reset there) - without this line a stale list could surface
        if (isLoading || !active) return;
        if ("tg".equals(serverMode)) {
            display.setCurrent(terminal);
            terminal.addLine(Strings.claudeOffHere(), TerminalCanvas.COLOR_ERROR);
            terminal.repaint();
            return;
        }
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        display.setCurrent(terminal);
        terminal.startSpinner(Strings.getLang() == 0 ? "загрузка..." : "loading...");
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    HttpConnection conn = openSigned(serverUrl + "/api/sessions", "GET", null);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        conn.close();
                        if (myEpoch != reqEpoch) return;
                        terminal.stopSpinner();
                        isLoading = false;
                        terminal.addLine(code == 404 ? Strings.claudeOffHere() : "HTTP " + code,
                                         TerminalCanvas.COLOR_ERROR);
                        terminal.repaint();
                        return;
                    }
                    InputStream is = conn.openInputStream();
                    String json = readUTF8(is);
                    is.close();
                    conn.close();
                    String current = extractField(json, "current");
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    isLoading = false;
                    showSessionsList(json, current, myEpoch);
                } catch (Exception e) {
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    isLoading = false;
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                    terminal.repaint();
                }
            }
        });
        t.start();
    }

    private void showSessionsList(String json, String current, final int myEpoch) {
        java.util.Vector ids = new java.util.Vector();
        java.util.Vector names = new java.util.Vector();
        int sf = 0;
        while (true) {
            int s1 = json.indexOf("\"session_id\":\"", sf);
            if (s1 < 0) break;
            s1 += 14;
            int s2 = json.indexOf("\"", s1);
            if (s2 < 0) break;
            String sid = json.substring(s1, s2);
            int n1 = json.indexOf("\"name\":\"", s2);
            if (n1 < 0) break;
            n1 += 8;
            int n2 = json.indexOf("\"", n1);
            if (n2 < 0) break;
            String name = json.substring(n1, n2);
            int mc1 = json.indexOf("\"message_count\":", n2);
            String mc = "0";
            if (mc1 >= 0) {
                mc1 += 16;
                int mc2 = mc1;
                while (mc2 < json.length() && json.charAt(mc2) >= '0' && json.charAt(mc2) <= '9') mc2++;
                mc = json.substring(mc1, mc2);
            }
            ids.addElement(sid);
            String label = name + " (" + mc + ")";
            if (sid.equals(current)) label = "> " + label;
            names.addElement(label);
            sf = n2 + 1;
        }
        sessionIds = new String[ids.size()];
        sessionNames = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            sessionIds[i] = (String) ids.elementAt(i);
            sessionNames[i] = (String) names.elementAt(i);
        }
        display.callSerially(new Runnable() {
            public void run() {
                if (myEpoch != reqEpoch || !active) return;   // cancel - do not push the list out
                sessionsList = new MenuCanvas(VibeNokiaMIDlet.this, Strings.sessions());
                if (sessionIds.length == 0) {
                    sessionsList.append(Strings.noSessions());
                } else {
                    for (int i = 0; i < sessionNames.length; i++)
                        sessionsList.append(sessionNames[i]);
                }
                Command delCmd = new Command(Strings.delete(), Command.SCREEN, 2);
                sessionsList.setSelectCommand(openItemCmd);
                sessionsList.addCommand(openItemCmd);
                sessionsList.addCommand(delCmd);
                sessionsList.addCommand(backCmd);
                sessionsList.setCommandListener(VibeNokiaMIDlet.this);
                display.setCurrent(sessionsList);
            }
        });
    }

    private void handleSessionAction(Command c) {
        int idx = sessionsList.getSelectedIndex();
        if (idx < 0 || sessionIds == null || idx >= sessionIds.length) return;
        if (c.getLabel().equals(Strings.delete())) {
            deleteSession(sessionIds[idx]);
        } else {
            switchToSession(sessionIds[idx], sessionNames[idx]);
        }
    }

    private void switchToSession(final String sid, final String name) {
        if (isLoading) return;
        // The epoch is taken AT SUBMISSION (like other workers) and grows immediately:
        // cancel/stop after this moment honestly kills the worker - no race
        final int myEpoch = ++reqEpoch;
        Thread t = new Thread(new Runnable() {
            public void run() {
                String result = null;
                try {
                    result = doPost(serverUrl + "/api/sessions/switch", "{\"session_id\":\"" + escapeJson(sid) + "\"}");
                    // The panel is determined by the SESSION's engine (the server knows its model),
                    // not by the current model - otherwise Claude would get a foreign OC session
                    String m = extractField(result, "model");
                    if (m != null && m.length() > 0) {
                        currentModel = m;
                        if (m.startsWith("opencode")) ocModel = m; else claudeModel = m;
                    }
                    chatOwner = currentModel.startsWith("opencode") ? PANEL_OC : PANEL_CLAUDE;
                    activePanel = chatOwner;
                    setSession(sid);
                } catch (Exception e) {}
                final String json = result;
                display.callSerially(new Runnable() {
                    public void run() {
                        if (myEpoch != reqEpoch) return;   // cancel - do not show the terminal
                        if (json == null) {
                            terminal.addLine(Strings.getLang() == 0 ? "сессия не открылась"
                                                                    : "session failed",
                                             TerminalCanvas.COLOR_ERROR);
                            display.setCurrent(terminal);
                            return;
                        }
                        terminal.clear();
                        updateHeader();
                        terminal.addLine("session: " + name, TerminalCanvas.COLOR_ACCENT);
                        terminal.addLine("", TerminalCanvas.COLOR_DIM);
                        showHistory(json);
                        display.setCurrent(terminal);
                    }
                });
            }
        });
        t.start();
    }

    private void showHistory(String json) {
        // Parse "history":[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]
        int hStart = json.indexOf("\"history\":[");
        if (hStart < 0) return;
        hStart += 11;
        // Find each message object
        int pos = hStart;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findObjEnd(json, objStart);  // '}' inside text is not an object boundary
            if (objEnd < 0) break;
            String obj = json.substring(objStart, objEnd + 1);
            String role = extractField(obj, "role");
            String content = extractField(obj, "content");
            if (role != null && content != null && content.length() > 0) {
                content = unescapeJson(content);
                if (role.equals("user")) {
                    addWrappedText("> " + content, TerminalCanvas.COLOR_INPUT);
                } else {
                    addWrappedText(content, TerminalCanvas.COLOR_RESPONSE);
                    terminal.addLine("", TerminalCanvas.COLOR_DIM);
                }
            }
            pos = objEnd + 1;
        }
    }

    private void deleteSession(final String sid) {
        final int myEpoch = reqEpoch;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    doPost(serverUrl + "/api/sessions/delete", "{\"session_id\":\"" + escapeJson(sid) + "\"}");
                    if (sid.equals(sessionId)) setSession("");
                } catch (Exception e) {}
                if (myEpoch != reqEpoch || !active) return;   // cancel: do not reload the list
                loadSessions();
            }
        });
        t.start();
    }

    // --- SERVERS ---

    /** Server list from RMS. If there is none - create it from the current address. */
    private void loadServers() {
        try {
            RecordStore rs = RecordStore.openRecordStore("servers", false);
            String s = new String(rs.getRecord(1), "UTF-8");
            rs.closeRecordStore();
            java.util.Vector names = new java.util.Vector();
            java.util.Vector urls = new java.util.Vector();
            int pos = 0;
            int idx = 0;
            boolean first = true;
            while (pos < s.length()) {
                int nl = s.indexOf('\n', pos);
                String line;
                if (nl >= 0) { line = s.substring(pos, nl); pos = nl + 1; }
                else { line = s.substring(pos); pos = s.length(); }
                if (first) {
                    first = false;
                    try { idx = Integer.parseInt(line.trim()); } catch (Exception e) { idx = 0; }
                    continue;
                }
                int bar = line.indexOf('|');
                if (bar > 0) {
                    names.addElement(line.substring(0, bar));
                    urls.addElement(line.substring(bar + 1));
                }
            }
            if (names.size() > 0) {
                serverNames = new String[names.size()];
                serverUrls = new String[urls.size()];
                for (int i = 0; i < names.size(); i++) {
                    serverNames[i] = (String) names.elementAt(i);
                    serverUrls[i] = (String) urls.elementAt(i);
                }
                serverIdx = (idx >= 0 && idx < serverNames.length) ? idx : 0;
                if (serverIdx < 0 || serverIdx >= serverUrls.length) serverIdx = 0;
                serverUrl = serverUrls[serverIdx];
            }
        } catch (Exception e) {
            // first launch has not happened yet - leave the presets as they are, do NOT overwrite them.
            // The presets already contain "Claude-2": this installation never needs the migration
            if (!backupMigrated) { backupMigrated = true; persistNetState(); }
            serverIdx = 0;
            for (int i = 0; i < serverUrls.length; i++) {
                if (serverUrls[i].equals(serverUrl)) { serverIdx = i; break; }
            }
            serverUrl = serverUrls[serverIdx];
        }
    }

    private void persistServers() {
        try {
            StringBuffer sb = new StringBuffer();
            sb.append(serverIdx);
            for (int i = 0; i < serverNames.length; i++) {
                sb.append('\n').append(serverNames[i]).append('|').append(serverUrls[i]);
            }
            byte[] data = sb.toString().getBytes("UTF-8");
            try {
                RecordStore rs = RecordStore.openRecordStore("servers", false);
                rs.setRecord(1, data, 0, data.length);
                rs.closeRecordStore();
            } catch (RecordStoreNotFoundException e) {
                RecordStore rs = RecordStore.openRecordStore("servers", true);
                rs.addRecord(data, 0, data.length);
                rs.closeRecordStore();
            }
        } catch (Exception e) {}
    }

    /** Key 1 in the terminal - cycles through the servers. */

    // --- Quick model choice (key 3) ---
    private MenuCanvas modelsList;

    public void onPickModel() {
        if (isLoading || tgMode || chatOwner == PANEL_NONE) return;
        showModelMenu(null);   // immediately from what we know (works offline too)
        loadModels(true, false);    // and right away a fresh list from the server: when it arrives, the menu is rebuilt
    }

    /** Model picker menu from the current list. keepId != null - cursor on it. */
    private void showModelMenu(String keepId) {
        boolean oc = (chatOwner == PANEL_OC);
        String[] ids = MODEL_IDS;          // snapshot: the list may change from the network
        String[] names = MODEL_NAMES;
        modelsList = new MenuCanvas(this, Strings.model());
        String[] pi = new String[ids.length];
        String[] pn = new String[ids.length];
        int n = 0, cur = -1, keep = -1;
        for (int i = 0; i < ids.length; i++) {
            boolean isOc = ids[i].startsWith("opencode");
            if (isOc != oc) continue;   // Claude gets Claude models, OpenCode its own
            boolean on = ids[i].equals(currentModel);
            if (on) cur = n;
            if (keepId != null && ids[i].equals(keepId)) keep = n;
            modelsList.append((on ? "> " : "  ") + names[i]);
            pi[n] = ids[i];
            pn[n] = names[i];
            n++;
        }
        pickIds = new String[n];
        pickNames = new String[n];
        System.arraycopy(pi, 0, pickIds, 0, n);
        System.arraycopy(pn, 0, pickNames, 0, n);
        int row = keep >= 0 ? keep : cur;
        if (row >= 0 && row < n) modelsList.setSelectedIndex(row);
        modelsList.setSelectCommand(openItemCmd);
        modelsList.addCommand(openItemCmd);
        modelsList.addCommand(backCmd);
        modelsList.setCommandListener(this);
        display.setCurrent(modelsList);
    }

    private void pickModel(int row) {
        if (pickIds == null || row < 0 || row >= pickIds.length) return;
        String id = pickIds[row];
        if (!id.equals(currentModel)) {
            setSession("");   // model change = new session
            currentModel = id;
            if (chatOwner == PANEL_OC) ocModel = currentModel; else claudeModel = currentModel;
            persistSettings();
        }
        updateHeader();
        terminal.addLine(Strings.model() + " " + pickNames[row], TerminalCanvas.COLOR_ACCENT);
        terminal.repaint();
        display.setCurrent(terminal);
    }

    private void selectServer(int idx) {
        if (idx < 0 || idx >= serverUrls.length) return;
        serverIdx = idx;
        serverUrl = serverUrls[idx];
        // Manual server choice for the "Claude" role = new current Claude address (and in RMS):
        // otherwise the first entry into the section would bring back the primary
        if (!isTgRoleUrl(serverUrl)) {
            synchronized (this) {
                fullFails = 0;
                fullDown = false;   // new address - check immediately, without the rare mode
                fullSearchPos = -1;
                if (!serverUrl.equals(liveFullUrl)) { liveFullUrl = serverUrl; persistNetState(); }
            }
        }
        serverMode = "";
        setSession("");   // the session belongs to the previous server - the new one does not know it
        persistServers();
        persistSettings();
        if (tgMode) { tgMode = false; tgChatId = ""; tgTitle = ""; chatOwner = PANEL_NONE; }
        updateHeader();
        terminal.addLine(Strings.serverSwitched() + " " + serverNames[idx], TerminalCanvas.COLOR_ACCENT);
        terminal.repaint();
        display.setCurrent(terminal);
        pingServerNow();
    }

    /** One-off ping: shows whether the server is alive and what it can do. */
    private void pingServerNow() {
        if (!active) return;              // when stopped the air stays silent
        final int myEpoch = reqEpoch;
        // The role is fixed at start: the result is shown only if the section has not changed
        final String startUrl = serverUrl;
        final boolean full = !isTgRoleUrl(startUrl);
        Thread t = new Thread(new Runnable() {
            public void run() {
                // one ping at a time: wait until the timer releases the connection
                boolean got = false;
                for (int i = 0; i < 40; i++) {
                    if (myEpoch != reqEpoch || !active) return;
                    if (beginPing()) { got = true; break; }
                    try { Thread.sleep(200); } catch (Exception ie) { break; }
                }
                if (!got) {
                    // the background check holds the connection longer than 8 s - do not stay silent
                    if (myEpoch != reqEpoch || !active) return;
                    if (isTgRoleUrl(serverUrl) == full) return;   // the section is already of another role
                    terminal.addLine("  " + (Strings.getLang() == 0
                        ? "Проверка связи уже идёт..." : "Connection check in progress..."),
                        TerminalCanvas.COLOR_DIM);
                    terminal.repaint();
                    return;
                }
                String[] body = new String[1];
                int code = -1;
                String answered = null;
                boolean aborted = false;
                try {
                    // Claude: the current live address first; if silent - probe immediately
                    // (the user asked for the check themselves, hysteresis is not needed).
                    // TG: no reserve - only its own address.
                    String first = full ? fullServerUrl() : startUrl;
                    code = probePing(first, body);
                    if (code == 200) {
                        answered = first;
                        if (full) { fullFails = 0; fullDown = false; }
                    } else if (full && !pingAborted && myEpoch == reqEpoch && active) {
                        String sw = failoverFull(first, myEpoch, body);
                        if (sw != null) { answered = sw; code = 200; }
                    }
                } finally {
                    aborted = pingAborted;
                    endPing();
                }
                // aborted by a user request - stay silent, this is not "server unavailable"
                if (aborted || myEpoch != reqEpoch || !active) return;
                // while the ping ran the user moved to a section of another role - do not touch a foreign header
                if (isTgRoleUrl(serverUrl) == full) return;
                terminal.setServerOnline(code == 200);
                if (code == 200) {
                    String m = extractField(body[0] != null ? body[0] : "", "mode");
                    if (answered != null && answered.equals(serverUrl)) serverMode = m != null ? m : "";
                    terminal.addLine("  " + Strings.serverOk()
                        + ("tg".equals(serverMode) ? " (" + Strings.tgOnly() + ")" : ""),
                        TerminalCanvas.COLOR_DIM);
                    if (answered != null && !answered.equals(startUrl) && full) noteFullSwitch(answered);
                } else {
                    terminal.addLine("  " + Strings.serverBad() + (code > 0 ? " HTTP " + code : ""),
                                     TerminalCanvas.COLOR_ERROR);
                }
                terminal.repaint();
            }
        });
        t.start();
    }

    private void showServersList() {
        serversList = new MenuCanvas(this, Strings.servers());
        for (int i = 0; i < serverNames.length; i++) {
            serversList.append((i == serverIdx ? "> " : "  ") + serverNames[i]);
        }
        Command editCmd = new Command(Strings.edit(), Command.SCREEN, 2);
        Command addCmd = new Command(Strings.addContact(), Command.SCREEN, 3);
        Command delCmd = new Command(Strings.delete(), Command.SCREEN, 4);
        serversList.setSelectCommand(openItemCmd);
        serversList.addCommand(openItemCmd);
        serversList.addCommand(editCmd);
        serversList.addCommand(addCmd);
        serversList.addCommand(delCmd);
        serversList.addCommand(backCmd);
        serversList.setCommandListener(this);
        display.setCurrent(serversList);
    }

    private void handleServersAction(Command c) {
        String label = c.getLabel();
        int idx = serversList.getSelectedIndex();
        if (label.equals(Strings.addContact())) { showServerEditForm(-1); return; }
        if (idx < 0 || idx >= serverUrls.length) return;
        if (label.equals(Strings.edit())) {
            showServerEditForm(idx);
        } else if (label.equals(Strings.delete())) {
            if (serverNames.length <= 1) return;   // never delete the last address
            String[] n2 = new String[serverNames.length - 1];
            String[] u2 = new String[serverUrls.length - 1];
            int k = 0;
            for (int i = 0; i < serverNames.length; i++) {
                if (i == idx) continue;
                n2[k] = serverNames[i];
                u2[k] = serverUrls[i];
                k++;
            }
            serverNames = n2;
            serverUrls = u2;
            // the index "drifted" after deletion - hold on to the active server's URL,
            // otherwise requests silently go to a neighbour while the header shows the old one
            int keep = 0;
            for (int i = 0; i < serverUrls.length; i++) {
                if (serverUrls[i].equals(serverUrl)) { keep = i; break; }
            }
            persistServers();
            selectServer(keep);   // fixes serverIdx/serverUrl/header/mode at once
            showServersList();
        } else {
            display.setCurrent(terminal);
            selectServer(idx);
        }
    }

    private void showServerEditForm(int idx) {
        editingServer = idx;
        inputPurpose = IP_SERVER_NAME;   // step 1: name, then ask for the address
        inputCanvas.open(Strings.name(), idx < 0 ? "" : serverNames[idx], 24);
        display.setCurrent(inputCanvas);
    }

    /** Server form step 2: address received from input - apply. */
    private void finishServerEdit(String url) {
        String name = pendingServerName;
        if (name.length() == 0) name = url;
        if (editingServer >= 0 && editingServer < serverNames.length) {
            String oldUrl = serverUrls[editingServer];
            serverNames[editingServer] = name;
            serverUrls[editingServer] = url;
            if (editingServer == serverIdx) serverUrl = url;
            // the live Claude slot (or the active Claude slot) was edited - the live address follows the edit
            if (oldUrl.equals(liveFullUrl) || (editingServer == serverIdx && !isTgRoleUrl(url))) {
                synchronized (this) {
                    liveFullUrl = isTgRoleUrl(url) ? null : url;
                    fullFails = 0;
                    fullDown = false;
                    fullSearchPos = -1;
                    persistNetState();
                }
            }
        } else {
            String[] n2 = new String[serverNames.length + 1];
            String[] u2 = new String[serverUrls.length + 1];
            for (int i = 0; i < serverNames.length; i++) { n2[i] = serverNames[i]; u2[i] = serverUrls[i]; }
            n2[serverNames.length] = name;
            u2[serverUrls.length] = url;
            serverNames = n2;
            serverUrls = u2;
        }
        persistServers();
        showServersList();
    }

    // --- TELEGRAM ---

    /** Bot contact list: those who wrote to the bot + manually added. */
    private void loadTgContacts() {
        // Same guard as in loadSessions: "added/deleted a contact" + cancel
        // during the request raised the contacts screen again
        if (isLoading || !active) return;
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        display.setCurrent(terminal);
        terminal.startSpinner(Strings.getLang() == 0 ? "контакты..." : "contacts...");
        Thread t = new Thread(new Runnable() {
            public void run() {
                HttpConnection conn = null;
                try {
                    conn = openSigned(serverUrl + "/api/tg/contacts", "GET", null);
                    int code = conn.getResponseCode();
                    if (myEpoch != reqEpoch) { conn.close(); return; }
                    if (code != 200) {
                        conn.close();
                        terminal.stopSpinner();
                        isLoading = false;
                        terminal.addLine("HTTP " + code + " — "
                            + (code == 404 ? "сервер без Телеграма" : Strings.serverBad()),
                            TerminalCanvas.COLOR_ERROR);
                        terminal.repaint();
                        return;
                    }
                    InputStream is = conn.openInputStream();
                    String json = readUTF8(is);
                    is.close();
                    conn.close();
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    isLoading = false;
                    boolean enabled = json.indexOf("\"enabled\":true") >= 0;
                    String bn = extractField(json, "bot");
                    tgBotName = (bn != null) ? bn : "";
                    parseTgContacts(json);
                    showTgContactsList(enabled, myEpoch);
                } catch (Exception e) {
                    // do not leave the connection open: S30+ has few of them, a leak chokes the next requests
                    if (conn != null) try { conn.close(); } catch (Exception ce) {}
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    isLoading = false;
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                    terminal.repaint();
                }
            }
        });
        t.start();
    }

    private void parseTgContacts(String json) {
        java.util.Vector ids = new java.util.Vector();
        java.util.Vector labels = new java.util.Vector();
        int sf = 0;
        while (true) {
            int s1 = json.indexOf("\"chat_id\":\"", sf);
            if (s1 < 0) break;
            s1 += 11;
            int s2 = json.indexOf("\"", s1);
            if (s2 < 0) break;
            String cid = json.substring(s1, s2);
            int n1 = json.indexOf("\"title\":\"", s2);
            if (n1 < 0) break;
            n1 += 9;
            int n2 = n1;
            while (n2 < json.length()) {
                char ch = json.charAt(n2);
                if (ch == '"' && json.charAt(n2 - 1) != '\\') break;
                n2++;
            }
            String title = unescapeJson(json.substring(n1, n2));
            // unread - the number after "unread" in the same object
            int u1 = json.indexOf("\"unread\":", n2);
            int unread = 0;
            if (u1 >= 0) {
                u1 += 9;
                int u2 = u1;
                while (u2 < json.length() && json.charAt(u2) >= '0' && json.charAt(u2) <= '9') u2++;
                if (u2 > u1) unread = Integer.parseInt(json.substring(u1, u2));
            }
            ids.addElement(cid);
            labels.addElement(unread > 0 ? (title + " (" + unread + ")") : title);
            sf = n2 + 1;
        }
        tgIds = new String[ids.size()];
        tgLabels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            tgIds[i] = (String) ids.elementAt(i);
            tgLabels[i] = (String) labels.elementAt(i);
        }
    }

    private void showTgContactsList(final boolean enabled, final int myEpoch) {
        display.callSerially(new Runnable() {
            public void run() {
                if (myEpoch != reqEpoch || !active) return;   // cancel - do not push the list out
                tgList = new MenuCanvas(VibeNokiaMIDlet.this, Strings.contacts());
                if (!enabled) {
                    tgList.append(Strings.tgOff());
                } else if (tgIds.length == 0) {
                    // Empty does not mean "broken": a bot cannot write first.
                    // Show which bot to share so that people appear.
                    tgList.append(Strings.noContacts());
                    tgList.append(Strings.tgInviteHow());
                    if (tgBotName.length() > 0) tgList.append("@" + tgBotName);
                } else {
                    for (int i = 0; i < tgLabels.length; i++) tgList.append(tgLabels[i]);
                }
                Command addCmd = new Command(Strings.addContact(), Command.SCREEN, 2);
                Command delCmd = new Command(Strings.delete(), Command.SCREEN, 3);
                tgList.setSelectCommand(openItemCmd);
                tgList.addCommand(openItemCmd);
                tgList.addCommand(addCmd);
                tgList.addCommand(delCmd);
                tgList.addCommand(backCmd);
                tgList.setCommandListener(VibeNokiaMIDlet.this);
                display.setCurrent(tgList);
            }
        });
    }

    private void handleTgListAction(Command c) {
        String label = c.getLabel();
        if (label.equals(Strings.addContact())) { showTgAddForm(); return; }
        int idx = tgList.getSelectedIndex();
        if (idx < 0 || tgIds == null || idx >= tgIds.length) return;
        if (label.equals(Strings.delete())) {
            deleteTgContact(tgIds[idx]);
        } else {
            openTgChat(tgIds[idx], stripUnread(tgLabels[idx]));
        }
    }

    private String stripUnread(String label) {
        // "Name (2)" -> "Name"; CLDC has no lastIndexOf(String), only by character
        if (label.endsWith(")")) {
            int p = label.lastIndexOf('(');
            if (p > 1 && label.charAt(p - 1) == ' ') {
                // strip only "(digits)" - a name like "Name (work)" is left alone
                boolean num = p + 1 < label.length() - 1;
                for (int i = p + 1; num && i < label.length() - 1; i++) {
                    char ci = label.charAt(i);
                    if (ci < '0' || ci > '9') num = false;
                }
                if (num) return label.substring(0, p - 1);
            }
        }
        return label;
    }

    private void showTgAddForm() {
        inputPurpose = IP_TG_ID;   // step 1: chat_id, then ask for the name
        inputCanvas.openNumeric(Strings.chatId(), "", 32);   // ids of groups with a minus fit
        display.setCurrent(inputCanvas);
    }

    private void addTgContact(final String cid, final String name) {
        if (cid.length() == 0) return;
        display.setCurrent(terminal);
        final int myEpoch = reqEpoch;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String body = "{\"chat_id\":\"" + escapeJson(cid) + "\",\"title\":\""
                                + escapeJson(name.length() > 0 ? name : cid) + "\"}";
                    doPost(serverUrl + "/api/tg/contacts/add", body);
                } catch (Exception e) {
                    if (myEpoch != reqEpoch || !active) return;   // cancelled: do not write into the terminal
                    String em = e.getMessage();
                    terminal.addLine("ERR: " + (em == null || em.length() == 0 ? e.getClass().getName() : em),
                                     TerminalCanvas.COLOR_ERROR);
                }
                if (myEpoch != reqEpoch || !active) return;
                loadTgContacts();
            }
        });
        t.start();
    }

    private void deleteTgContact(final String cid) {
        final int myEpoch = reqEpoch;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    doPost(serverUrl + "/api/tg/contacts/delete", "{\"chat_id\":\"" + escapeJson(cid) + "\"}");
                    // the contact with whom the chat is open was deleted - leave TG mode completely,
                    // otherwise a dead screen remains where one can neither send nor refresh
                    if (myEpoch != reqEpoch || !active) return;   // stopped: do not send the user home
                    if (cid.equals(tgChatId)) goHome();
                } catch (Exception e) {}
                if (myEpoch != reqEpoch || !active) return;
                loadTgContacts();
            }
        });
        t.start();
    }

    /** Open the conversation with a contact - the terminal switches to Telegram mode. */
    private void openTgChat(String cid, String title) {
        tgMode = true;
        activePanel = PANEL_TG;
        chatOwner = PANEL_TG;   // the terminal is now occupied by the TG chat
        tgChatId = cid;
        tgTitle = title;
        updateHeader();
        terminal.clear();
        display.setCurrent(terminal);
        loadTgMessages(true);
    }


    /** Pull the latest conversation messages (incoming + outgoing). */
    private volatile boolean autoRefreshing = false;  // background refresh of the TG chat
    private volatile boolean pingBusy = false;        // a ping is holding the connection right now

    private void loadTgMessages(final boolean withHints) {
        if (isLoading || autoRefreshing || tgChatId.length() == 0 || !active) return;
        final int myEpoch = reqEpoch;
        // A manual refresh blocks input and spins the spinner; a BACKGROUND one is quiet:
        // it does not touch isLoading, otherwise it ate the typed message and the buttons
        if (withHints) {
            isLoading = true;
            terminal.startSpinner(Strings.getLang() == 0 ? "загрузка..." : "loading...");
        } else {
            autoRefreshing = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                String json = null;
                HttpConnection conn = null;
                try {
                    conn = openSigned(
                        serverUrl + "/api/tg/messages?chat_id=" + tgChatId + "&limit=12", "GET", null);
                    InputStream is = conn.openInputStream();
                    json = readUTF8(is);
                    is.close();
                } catch (Exception e) {
                    if (withHints && myEpoch == reqEpoch && active)
                        terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                } finally {
                    if (conn != null) try { conn.close(); } catch (Exception ce) {}
                }
                if (myEpoch != reqEpoch || !active) {   // cancel: release the flags, do not touch the screen
                    if (withHints) { isLoading = false; terminal.stopSpinner(); }
                    else autoRefreshing = false;
                    return;
                }
                if (withHints) {
                    terminal.stopSpinner();
                    isLoading = false;
                } else {
                    autoRefreshing = false;
                    // quiet background: failed or the user left the chat - just exit
                    if (json == null || !tgMode || display.getCurrent() != terminal || isLoading) return;
                }
                terminal.clear();
                terminal.addLine(" " + tgTitle, TerminalCanvas.COLOR_ACCENT);
                if (withHints) {
                    terminal.addLine(" " + Strings.tgHint1(), TerminalCanvas.COLOR_DIM);
                    terminal.addLine(" " + Strings.tgHint2(), TerminalCanvas.COLOR_DIM);
                    terminal.addLine(" " + Strings.tgHint3(), TerminalCanvas.COLOR_DIM);
                }
                terminal.addLine("", TerminalCanvas.COLOR_DIM);
                if (json != null) showTgMessages(json);
                terminal.repaint();
            }
        });
        t.start();
    }

    private void showTgMessages(String json) {
        int arr = json.indexOf("\"messages\":[");
        if (arr < 0) return;
        int pos = arr + 12;
        boolean any = false;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findObjEnd(json, objStart);  // '}' inside text is not an object boundary
            if (objEnd < 0) break;
            String obj = json.substring(objStart, objEnd + 1);
            String dir = extractField(obj, "direction");
            String kind = extractField(obj, "kind");
            String text = extractField(obj, "text");
            String tm = extractField(obj, "time");
            if (text != null && text.length() > 0) {
                any = true;
                String prefix = "voice".equals(kind) ? "~ " : "";
                String suffix = (tm != null && tm.length() > 0) ? "  [" + tm + "]" : "";
                if ("out".equals(dir)) {
                    addWrappedText("> " + prefix + unescapeJson(text) + suffix, TerminalCanvas.COLOR_INPUT);
                } else {
                    addWrappedText("< " + prefix + unescapeJson(text) + suffix, TerminalCanvas.COLOR_RESPONSE);
                }
            }
            pos = objEnd + 1;
        }
        if (!any) terminal.addLine(" " + Strings.tgNoMessages(), TerminalCanvas.COLOR_DIM);
    }

    /** Text message to a contact in Telegram. */
    private void sendTgText(final String message) {
        if (isLoading || tgChatId.length() == 0) return;
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        addWrappedText("> " + message, TerminalCanvas.COLOR_INPUT);
        terminal.startSpinner(Strings.sending());
        setLoadingCommands();

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    StringBuffer json = new StringBuffer();
                    json.append("{\"chat_id\":\"").append(escapeJson(tgChatId));
                    json.append("\",\"text\":\"").append(escapeJson(message)).append("\"}");
                    String result = doPostTracked(serverUrl + "/api/tg/send", json.toString(), myEpoch);
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    handleTgResult(result, Strings.tgSent());
                } catch (Exception e) {
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                }
                if (myEpoch != reqEpoch) return;
                isLoading = false;
                setNormalCommands();
                terminal.repaint();
            }
        });
        t.start();
    }

    /** Voice to a contact in Telegram: AMR from Nokia -> server -> voice message in the chat. */
    private void sendTgVoice() {
        if (isLoading || audioStream == null || tgChatId.length() == 0) return;
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        final int seconds = recordTimer;
        terminal.setVoiceState(TerminalCanvas.VOICE_NONE, 0);
        setLoadingCommands();

        final byte[] audioData = audioStream.toByteArray();
        audioStream = null;

        Thread t = new Thread(new Runnable() {
            public void run() {
                HttpConnection conn = null;
                try {
                    terminal.startSpinner(Strings.sending());
                    waitNetIdle();
                    if (myEpoch != reqEpoch) return;   // cancel while waiting for the network
                    final String endpoint = serverUrl + "/api/tg/voice";   // ONCE: the connection address and the signature (X-Host) must match
                    conn = (HttpConnection) Connector.open(endpoint);
                    activeConn = conn;   // volatile: cancel will close even this connection
                    if (myEpoch != reqEpoch) {           // died while opening
                        try { conn.close(); } catch (Exception ce) {}
                        activeConn = null;
                        return;                          // do not send the body
                    }
                    conn.setRequestMethod(HttpConnection.POST);
                    conn.setRequestProperty("Content-Type", "audio/amr");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    DeviceAuth.sign(conn, endpoint, "POST", audioData);
                    conn.setRequestProperty("X-Chat-Id", tgChatId);
                    conn.setRequestProperty("X-Transcribe", "1");  // caption-transcription for the voice message
                    conn.setRequestProperty("Content-Length", String.valueOf(audioData.length));
                    OutputStream os = conn.openOutputStream();
                    os.write(audioData);
                    os.flush();
                    os.close();
                    int code = conn.getResponseCode();
                    InputStream is = conn.openInputStream();
                    String result = readUTF8(is);
                    is.close();
                    conn.close();
                    activeConn = null;
                    if (code != 200) throw new IOException("HTTP " + code);
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    addWrappedText("> ~ " + Strings.tgVoiceSent() + " (" + seconds + "s)",
                                   TerminalCanvas.COLOR_INPUT);
                    handleTgResult(result, null);
                } catch (Exception e) {
                    activeConn = null;
                    // HTTP error/timeout: close, otherwise the connection leaks (S30+ has few of them)
                    if (conn != null) try { conn.close(); } catch (Exception ce) {}
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                }
                if (myEpoch != reqEpoch) return;
                isLoading = false;
                setNormalCommands();
                terminal.repaint();
            }
        });
        t.start();
    }

    /** Parse the server's send response: ok / Telegram error text. */
    private void handleTgResult(String result, String okLine) {
        String status = extractField(result, "status");
        if ("ok".equals(status)) {
            if (okLine != null) terminal.addLine("  " + okLine, TerminalCanvas.COLOR_DIM);
        } else {
            String err = extractField(result, "error");
            String msg = (err == null || err.length() == 0)
                ? (Strings.getLang() == 0 ? "не отправлено, попробуй ещё" : "not sent, retry")
                : unescapeJson(err);
            terminal.addLine("ERR: " + msg, TerminalCanvas.COLOR_ERROR);
        }
    }

    // --- OTA UPDATE ---

    private void checkForUpdate() {
        if (isLoading) return;
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        terminal.startSpinner(Strings.getLang() == 0 ? "проверка..." : "checking...");

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    HttpConnection conn = openSigned(serverUrl + "/api/update", "GET", null);
                    InputStream is = conn.openInputStream();
                    String json = readUTF8(is);
                    is.close();
                    conn.close();

                    String serverVersion = extractField(json, "version");
                    String jadUrl = extractField(json, "jad_url");

                    if (myEpoch != reqEpoch) return;   // cancelled - do not start the installer
                    terminal.stopSpinner();

                    if (serverVersion != null && !serverVersion.equals(APP_VERSION) && jadUrl != null) {
                        terminal.addLine("v" + APP_VERSION + " -> v" + serverVersion, TerminalCanvas.COLOR_ACCENT);
                        terminal.addLine(Strings.getLang() == 0 ? "запускаю установку..." : "starting install...", TerminalCanvas.COLOR_DIM);
                        terminal.repaint();
                        try { Thread.sleep(500); } catch (Exception e) {}
                        // A 500 ms pause is a window for cancel: without a re-check
                        // the system JAD installer opened after a cancel
                        if (myEpoch != reqEpoch || !active) return;
                        boolean mustExit = platformRequest(jadUrl);
                        // The installer cannot replace a running app: if the platform
                        // requires exit - save and close, otherwise the installation hangs
                        if (mustExit) {
                            persistSettings();
                            persistServers();
                            try { Thread.sleep(300); } catch (Exception e) {}
                            destroyApp(true);
                        }
                    } else {
                        terminal.addLine(Strings.getLang() == 0
                            ? "v" + APP_VERSION + " - последняя"
                            : "v" + APP_VERSION + " - latest", TerminalCanvas.COLOR_DIM);
                    }
                } catch (Exception e) {
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                }
                if (myEpoch != reqEpoch) return;
                isLoading = false;
                terminal.repaint();
            }
        });
        t.start();
    }

    // --- TEXT ---

    private void sendTextMessage(final String message) {
        if (isLoading) return;
        if ("tg".equals(serverMode)) {   // the public Claude gateway does not launch it
            terminal.addLine(Strings.claudeOffHere(), TerminalCanvas.COLOR_ERROR);
            terminal.repaint();
            return;
        }
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        addWrappedText("> " + message, TerminalCanvas.COLOR_INPUT);
        terminal.startSpinner(Strings.thinking());
        setLoadingCommands();

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    StringBuffer json = new StringBuffer();
                    json.append("{\"message\":\"").append(escapeJson(message));
                    json.append("\",\"session_id\":\"").append(escapeJson(sessionId));
                    json.append("\",\"model\":\"").append(escapeJson(currentModel));
                    json.append("\",\"lang\":\"").append(escapeJson(Strings.langCode()));
                    json.append("\"}");
                    long t0 = System.currentTimeMillis();
                    // No per-request probing: /api/chat is not idempotent (a retry
                    // on a read timeout = a second Claude run and double billing).
                    // Liveness comes from the ping: every 5 s it switches serverUrl to
                    // the live relay, so the next request already goes where it should.
                    String result = doPostTracked(serverUrl + "/api/chat", json.toString(), myEpoch);
                    if (myEpoch != reqEpoch) return;   // cancelled/switched - do not touch the screen
                    terminal.stopSpinner();
                    // a long reply has matured - vibrate gently so it is not missed.
                    // active guard: the worker may have outlived the app - no vibration then
                    if (System.currentTimeMillis() - t0 > 8000 && active) {
                        try { display.vibrate(200); } catch (Exception ve) {}
                    }
                    showActions(result);
                    handleResponse(result);
                } catch (Exception e) {
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                }
                if (myEpoch != reqEpoch) return;
                terminal.addLine("", TerminalCanvas.COLOR_DIM);
                isLoading = false;
                setNormalCommands();
                terminal.repaint();
            }
        });
        t.start();
    }

    /** Human-readable error text by HTTP code instead of "ERR: HTTP 403". */
    private String httpError(Exception e) {
        String m = e.getMessage();
        if (m == null) m = "";
        if (m.indexOf("403") >= 0) return Strings.getLang() == 0
            ? "Телефон не привязан: Menu - Привязать заново" : "Not linked: Menu - Link again";
        if (m.indexOf("429") >= 0) return Strings.getLang() == 0 ? "Сервер заблокировал (много ошибок ключа), подожди" : "Rate-limited, wait";
        if (m.indexOf("404") >= 0) return Strings.getLang() == 0 ? "Этого нет на сервере" : "Not found on server";
        if (m.indexOf("500") >= 0 || m.indexOf("502") >= 0 || m.indexOf("503") >= 0) return Strings.getLang() == 0 ? "Ошибка сервера" : "Server error";
        if (m.length() == 0) return Strings.getLang() == 0 ? "Нет связи (обрыв/таймаут)" : "No connection (timeout)";
        return (Strings.getLang() == 0 ? "Нет связи: " : "No connection: ") + m;
    }

    // --- VOICE ---

    private void startRecording() {
        if (!Features.on(Features.VOICE)) return;
        if (isRecording || isLoading) return;
        // IMPORTANT: NOT on the key thread - the microphone permission popup cannot
        // be drawn there, and J9 interrupts the recording call ("Interrupted while waiting")
        new Thread(new Runnable() { public void run() { doStartRecording(); } }).start();
    }

    private void doStartRecording() {
        final int myEpoch = reqEpoch;   // cancel can wedge into microphone initialization
        synchronized (recLock) {
            if (isRecording || isLoading) return;
            isRecording = true;
        }
        try {
            if (audioPlayer == null || recordControl == null) { closePlayer(); initAudioPlayer(); }
            if (recordControl == null) throw new IOException("mic init");

            audioStream = new ByteArrayOutputStream();
            recordControl.setRecordStream(audioStream);
            recordControl.startRecord();
            audioPlayer.start();

            // Cancel fired while the microphone was initializing: silently give the
            // microphone back (without commit), do not show the REC banner and commands.
            // The Player is NOT closed: recreating it triggers the system microphone dialog
            if (myEpoch != reqEpoch) {
                try { recordControl.stopRecord(); } catch (Exception e) {}
                try { audioPlayer.stop(); } catch (Exception e) {}
                audioStream = null;
                terminal.setVoiceState(TerminalCanvas.VOICE_NONE, 0);
                setNormalCommands();
                return;
            }

            recordTimer = 0;
            terminal.setVoiceState(TerminalCanvas.VOICE_RECORDING, 0);
            setRecordingCommands();

            timerThread = new Thread(new Runnable() {
                public void run() {
                    while (isRecording) {
                        try { Thread.sleep(1000); } catch (Exception e) { break; }
                        if (isRecording) {
                            recordTimer++;
                            terminal.setVoiceState(TerminalCanvas.VOICE_RECORDING, recordTimer);
                            if (recordTimer >= 30) { stopRecording(true); break; }
                        }
                    }
                }
            });
            timerThread.start();
        } catch (Exception e) {
            synchronized (recLock) { isRecording = false; }
            audioStream = null;
            closePlayer();   // a broken engine will be recreated on the next recording
            if (myEpoch != reqEpoch) return;   // cancelled: do not write the error into the terminal
            terminal.setVoiceState(TerminalCanvas.VOICE_NONE, 0);
            setNormalCommands();
            terminal.addLine(Strings.getLang() == 0 ? "гс: сбой записи, попробуй ещё"
                                                    : "voice: rec failed, retry",
                             TerminalCanvas.COLOR_ERROR);
            terminal.repaint();
        }
    }

    private void stopRecording(boolean keepAudio) {
        synchronized (recLock) {   // exactly one thread does the commit (key/timer/minimize)
            if (!isRecording) return;
            isRecording = false;
        }
        boolean ok = true;
        try {
            if (recordControl != null) { recordControl.stopRecord(); recordControl.commit(); }
        } catch (Exception e) { ok = false; }
        try {
            if (audioPlayer != null) audioPlayer.stop();
        } catch (Exception e) { ok = false; }
        if (!ok) closePlayer();   // a broken engine will be recreated on the next recording

        if (keepAudio && ok && audioStream != null && audioStream.size() > 0) {
            terminal.setVoiceState(TerminalCanvas.VOICE_READY, recordTimer);
            setVoiceReadyCommands();
        } else {
            audioStream = null;
            terminal.setVoiceState(TerminalCanvas.VOICE_NONE, 0);
            setNormalCommands();
            if (keepAudio && !ok) {
                terminal.addLine(Strings.getLang() == 0 ? "гс: сбой записи, попробуй ещё"
                                                        : "voice: rec failed, retry",
                                 TerminalCanvas.COLOR_ERROR);
                terminal.repaint();
            }
        }
    }

    private void cancelVoice() {
        if (isRecording) stopRecording(false);
        audioStream = null;
        terminal.setVoiceState(TerminalCanvas.VOICE_NONE, 0);
        terminal.addLine(Strings.voiceCancelled(), TerminalCanvas.COLOR_ACCENT);
        setNormalCommands();
    }

    private void sendVoiceMessage() {
        if (tgMode) { sendTgVoice(); return; }
        if (isLoading || audioStream == null) return;
        if ("tg".equals(serverMode)) {
            audioStream = null;
            terminal.setVoiceState(TerminalCanvas.VOICE_NONE, 0);
            setNormalCommands();
            terminal.addLine(Strings.claudeOffHere(), TerminalCanvas.COLOR_ERROR);
            terminal.repaint();
            return;
        }
        isLoading = true;
        final int myEpoch = ++reqEpoch;
        terminal.setVoiceState(TerminalCanvas.VOICE_NONE, 0);
        setLoadingCommands();

        final byte[] audioData = audioStream.toByteArray();
        audioStream = null;

        Thread t = new Thread(new Runnable() {
            public void run() {
                HttpConnection conn = null;
                try {
                    // Upload audio -> server does Whisper + Claude in one shot
                    terminal.startSpinner(Strings.sending());
                    waitNetIdle();
                    if (myEpoch != reqEpoch) return;   // cancel while waiting for the network
                    final String endpoint = serverUrl + "/api/voice";   // ONCE: the connection address and the signature (X-Host) must match
                    conn = (HttpConnection) Connector.open(endpoint);
                    activeConn = conn;   // volatile: cancel will close even this connection
                    if (myEpoch != reqEpoch) {           // died while opening
                        try { conn.close(); } catch (Exception ce) {}
                        activeConn = null;
                        return;                          // do not send the body
                    }
                    conn.setRequestMethod(HttpConnection.POST);
                    conn.setRequestProperty("Content-Type", "audio/amr");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    DeviceAuth.sign(conn, endpoint, "POST", audioData);
                    conn.setRequestProperty("X-Session-Id", sessionId);
                    conn.setRequestProperty("X-Model", currentModel);
                    conn.setRequestProperty("X-Lang", Strings.langCode());
                    conn.setRequestProperty("Content-Length", String.valueOf(audioData.length));
                    OutputStream os = conn.openOutputStream();
                    os.write(audioData);
                    os.flush();

                    terminal.updateSpinner(Strings.thinking());
                    os.close();
                    int code = conn.getResponseCode();
                    InputStream is = conn.openInputStream();
                    String result = readUTF8(is);
                    is.close();
                    conn.close();
                    activeConn = null;
                    if (code != 200) throw new IOException("HTTP " + code);
                    if (myEpoch != reqEpoch) return;

                    // Show transcript if available
                    String transcript = extractField(result, "transcript");
                    if (transcript != null && transcript.length() > 0) {
                        addWrappedText("> " + unescapeJson(transcript), TerminalCanvas.COLOR_INPUT);
                    }

                    terminal.stopSpinner();
                    showActions(result);
                    handleResponse(result);
                } catch (Exception e) {
                    activeConn = null;
                    // HTTP error/timeout: close, otherwise the connection leaks (S30+ has few of them)
                    if (conn != null) try { conn.close(); } catch (Exception ce) {}
                    if (myEpoch != reqEpoch) return;
                    terminal.stopSpinner();
                    terminal.addLine(httpError(e), TerminalCanvas.COLOR_ERROR);
                }
                if (myEpoch != reqEpoch) return;
                terminal.addLine("", TerminalCanvas.COLOR_DIM);
                isLoading = false;
                setNormalCommands();
                terminal.repaint();
            }
        });
        t.start();
    }

    // --- RESPONSE ---

    private void showActions(String result) {
        String actions = extractField(result, "actions");
        if (actions != null && actions.length() > 0) {
            // Split by comma and show each action
            int start = 0;
            while (start < actions.length()) {
                int comma = actions.indexOf(',', start);
                String action;
                if (comma >= 0) { action = actions.substring(start, comma); start = comma + 1; }
                else { action = actions.substring(start); start = actions.length(); }
                if (action.length() > 0) {
                    terminal.addLine(" > " + action, TerminalCanvas.COLOR_ACCENT);
                }
            }
        }
    }

    private void handleResponse(String result) {
        String response = extractField(result, "response");
        String newSid = extractField(result, "session_id");
        if (newSid != null && newSid.length() > 0) setSession(newSid);
        if (response != null && response.length() > 0) {
            addWrappedText(unescapeJson(response), TerminalCanvas.COLOR_RESPONSE);
        } else {
            terminal.addLine(Strings.emptyResponse(), TerminalCanvas.COLOR_ERROR);
        }
        // Price of a paid model reply (OpenCode Go) - so that spending is visible
        String cost = extractField(result, "cost");
        if (cost != null && cost.length() > 0) {
            terminal.addLine("  " + cost, TerminalCanvas.COLOR_DIM);
        }
    }

    private void addWrappedText(String text, int color) {
        Font f = terminal.getFont();
        int maxW = terminal.getContentWidth();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            String seg;
            if (nl >= 0) { seg = text.substring(start, nl); start = nl + 1; }
            else { seg = text.substring(start); start = text.length(); }
            if (seg.length() == 0) { terminal.addLine("", color); continue; }
            int pos = 0;
            while (pos < seg.length()) {
                int end = pos;
                int lastSpace = -1;
                int w = 0;
                while (end < seg.length()) {
                    w += f.charWidth(seg.charAt(end));
                    if (w > maxW) break;
                    if (seg.charAt(end) == ' ') lastSpace = end;
                    end++;
                }
                if (end >= seg.length()) { terminal.addLine(seg.substring(pos), color); break; }
                if (lastSpace > pos) { terminal.addLine(seg.substring(pos, lastSpace), color); pos = lastSpace + 1; }
                else { terminal.addLine(seg.substring(pos, end), color); pos = end; }
            }
        }
    }

    // --- HTTP ---

    /** Connection signed with THIS phone's key. The body is the same as what goes to the server. */
    private HttpConnection openSigned(String url, String method, byte[] body) throws IOException {
        yieldPing();   // a background ping does not hold the channel before a user request
        return openSignedRaw(url, method, body);
    }

    /** Same without yielding to the ping - ONLY for the ping itself (it is the holder). */
    private HttpConnection openSignedRaw(String url, String method, byte[] body) throws IOException {
        HttpConnection conn = (HttpConnection) Connector.open(url);
        conn.setRequestMethod("POST".equals(method) ? HttpConnection.POST : HttpConnection.GET);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try {
            DeviceAuth.sign(conn, url, method, body);
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ce) {}   // do not hold the connection after a signing failure
            throw new IOException("sign");
        }
        return conn;
    }

    private String readUTF8(InputStream is) throws IOException {
        InputStreamReader reader = new InputStreamReader(is, "UTF-8");
        StringBuffer buf = new StringBuffer();
        int ch;
        while ((ch = reader.read()) != -1) buf.append((char) ch);
        return buf.toString();
    }

    private String doPostTracked(String endpoint, String jsonBody, int myEpoch) throws IOException {
        waitNetIdle();   // do not collide with an in-flight ping over the only connection
        if (myEpoch != reqEpoch) throw new IOException("stale");   // cancel while waiting
        HttpConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            conn = (HttpConnection) Connector.open(endpoint);
            activeConn = conn;   // volatile: cancel will close even this connection
            if (myEpoch != reqEpoch) throw new IOException("stale");   // died while opening
            conn.setRequestMethod(HttpConnection.POST);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            byte[] body = jsonBody.getBytes("UTF-8");
            DeviceAuth.sign(conn, endpoint, "POST", body);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            os = conn.openOutputStream();
            os.write(body);
            os.flush();
            int code = conn.getResponseCode();
            is = conn.openInputStream();
            String result = readUTF8(is);
            if (code != 200) throw new IOException("HTTP " + code);
            return result;
        } finally {
            activeConn = null;
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (os != null) try { os.close(); } catch (Exception e) {}
            if (conn != null) try { conn.close(); } catch (Exception e) {}
        }
    }

    private String doPost(String endpoint, String jsonBody) throws IOException {
        yieldPing();
        HttpConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            conn = (HttpConnection) Connector.open(endpoint);
            conn.setRequestMethod(HttpConnection.POST);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            byte[] body = jsonBody.getBytes("UTF-8");
            DeviceAuth.sign(conn, endpoint, "POST", body);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            os = conn.openOutputStream();
            os.write(body);
            os.flush();
            int code = conn.getResponseCode();
            is = conn.openInputStream();
            String result = readUTF8(is);
            if (code != 200) throw new IOException("HTTP " + code);
            return result;
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (os != null) try { os.close(); } catch (Exception e) {}
            if (conn != null) try { conn.close(); } catch (Exception e) {}
        }
    }

    // --- JSON ---

    /** Numeric field from JSON ("unread":3). Missing/not a number -> -1. */
    private int extractInt(String json, String field) {
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker);
        if (start < 0) return -1;
        start += marker.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c < '0' || c > '9') break;
            end++;
        }
        if (end == start) return -1;
        try { return Integer.parseInt(json.substring(start, end)); } catch (Exception e) { return -1; }
    }

    /** Wait until the background ping/refresh releases the only HTTP connection. */
    private void waitNetIdle() {
        // The ping is aborted and waited for no longer than 1 s: if J9 does not interrupt its connection,
        // waiting 8 s for the ping gains nothing (the request still stands behind it in the stack),
        // and a "stuck" ping would add 8 s to EVERY message/voice.
        yieldPing();
        // really wait only for the background TG refresh (it cannot be interrupted)
        for (int i = 0; i < 40 && autoRefreshing; i++) {
            try { Thread.sleep(200); } catch (Exception ie) { break; }
        }
    }

    /** End of a JSON object honoring quotes: '}' inside a string is not a boundary. */
    private int findObjEnd(String json, int objStart) {
        boolean inStr = false;
        for (int k = objStart; k < json.length(); k++) {
            char ck = json.charAt(k);
            if (inStr) {
                if (ck == '\\') k++;
                else if (ck == '"') inStr = false;
            } else if (ck == '"') {
                inStr = true;
            } else if (ck == '}') {
                return k;
            }
        }
        return -1;
    }

    private String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"' && (end == start || json.charAt(end - 1) != '\\')) break;
            end++;
        }
        if (end >= json.length()) return null;
        return json.substring(start, end);
    }

    private String escapeJson(String text) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private String unescapeJson(String text) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char n = text.charAt(i + 1);
                switch (n) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
