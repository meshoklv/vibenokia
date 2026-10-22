package vibenokia;

import javax.microedition.io.HttpConnection;
import javax.microedition.rms.RecordStore;

/**
 * The phone's own key instead of a shared key baked into the application file.
 *
 * Pairing is done ONCE (one-time code); afterwards the key is stored in phone
 * memory (RMS) and survives application updates, so nothing has to be entered
 * on each start. The key itself never travels over the network again: every request
 * is signed with it, plus a counter so that an intercepted request cannot be
 * replayed. The phone clock is not used because it drifts on Nokia devices.
 */
final class DeviceAuth {

    private static final String STORE = "devauth";

    private static String deviceId = "";
    private static String secret = "";
    private static int counter = 0;     // number of the current request
    private static int reserved = 0;    // up to which number storage writes have already been "paid" for
    private static boolean loaded = false;

    // A ping every 5 seconds is 17 thousand requests per day. Writing the counter to
    // storage on every request would wear out the phone's flash and slow down every
    // request, so numbers are reserved in blocks: one write per BLOCK requests, and
    // after a reboot the counter continues from the end of the block (the server only cares about growth).
    private static final int BLOCK = 100;

    private DeviceAuth() {}

    // --- storage ---

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE, false);
            if (rs.getNumRecords() > 0) {
                String s = new String(rs.getRecord(1), "UTF-8");
                int p1 = s.indexOf('|');
                int p2 = p1 >= 0 ? s.indexOf('|', p1 + 1) : -1;
                if (p1 > 0 && p2 > p1) {
                    deviceId = s.substring(0, p1);
                    secret = s.substring(p1 + 1, p2);
                    try { reserved = Integer.parseInt(s.substring(p2 + 1)); } catch (Exception e) { reserved = 0; }
                    counter = reserved;      // continue from the end of the reserved block
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
            // no storage yet - the phone is simply not paired
        }
    }

    private static synchronized void save() {
        try {
            byte[] d = (deviceId + "|" + secret + "|" + reserved).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length);
            else rs.addRecord(d, 0, d.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    static boolean isPaired() {
        load();
        return deviceId.length() > 0 && secret.length() > 0;
    }

    static String id() { load(); return deviceId; }

    /** Pairing succeeded: remember the credentials issued to the phone. */
    static synchronized void setPaired(String id, String key) {
        deviceId = id != null ? id : "";
        secret = key != null ? key : "";
        counter = 0;
        reserved = 0;
        loaded = true;
        save();
    }

    /** New key after reissue. The counter is NOT reset - the server expects it to grow. */
    static synchronized void setSecret(String key) {
        if (key == null || key.length() == 0) return;
        secret = key;
        save();
    }

    static synchronized void forget() {
        deviceId = "";
        secret = "";
        counter = 0;
        reserved = 0;
        save();
    }

    /** Next request number. Always grows, even if the request failed. */
    private static synchronized int nextCounter() {
        counter++;
        if (counter > reserved) {          // block exhausted - reserve the next one
            reserved = counter + BLOCK;
            save();
        }
        return counter;
    }

    /**
     * Sign a request. The signature covers exactly what the server sees:
     * method, path with parameters, request number and body fingerprint.
     */
    static void sign(HttpConnection conn, String url, String method, byte[] body)
            throws java.io.IOException {
        load();
        if (deviceId.length() == 0 || secret.length() == 0) return;   // not paired - let the server reject it
        int cnt = nextCounter();
        String path = pathOf(url);
        String host = hostOf(url);
        String msg = method + "\n" + path + "\n" + cnt + "\n"
                   + Crypto.sha256Hex(body != null ? body : new byte[0]) + "\n" + host;
        conn.setRequestProperty("X-Dev", deviceId);
        conn.setRequestProperty("X-Cnt", String.valueOf(cnt));
        conn.setRequestProperty("X-Host", host);
        conn.setRequestProperty("X-Sig", Crypto.hmacHex(secret, msg));
    }

    /** From "http://host:port/api/chat" get "host:port" - the address is part of the signature. */
    static String hostOf(String url) {
        int scheme = url.indexOf("://");
        int from = scheme >= 0 ? scheme + 3 : 0;
        int slash = url.indexOf('/', from);
        return slash < 0 ? url.substring(from) : url.substring(from, slash);
    }

    /** From "http://host:port/api/chat?x=1" get "/api/chat?x=1". */
    static String pathOf(String url) {
        int scheme = url.indexOf("://");
        int from = scheme >= 0 ? scheme + 3 : 0;
        int slash = url.indexOf('/', from);
        if (slash < 0) return "/";
        return url.substring(slash);
    }
}
