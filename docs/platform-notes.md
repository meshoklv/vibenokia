# Platform notes (Nokia 3310 2017, TA-1030, Series 30+)

Findings from building the client. "Observed" = seen on the developer's own handset;
"reported" = from public sources, not re-verified here.

| Topic | Notes |
|---|---|
| Runtime | Java ME, CLDC 1.1, MIDP 2.0, 240x320 screen. Series 30+ (MediaTek MT6260). |
| Network | Mobile data only (no Wi-Fi). Plain `HttpConnection` POST/JSON works (observed). |
| HTTPS | Reported to stop at old TLS versions with an old CA store, so modern endpoints fail. The client therefore uses plain HTTP and signs requests itself. |
| Response size | Keep replies small (the server cuts at ~25 KB; the screen shows far less). |
| Timeouts | No API to set them; the stack default is minutes, so the client runs its own watchdog threads. |
| Crypto | CLDC has no crypto. SHA-256 and HMAC are implemented by hand in `Crypto.java`. |
| Lists/forms | `List`/`Form` do not deliver `keyRepeated`, so everything is a custom `Canvas`. |
| Voice | JSR-135 audio capture to AMR (~1.5 KB/s); the server converts and transcribes it. |
| Side-loading | Via a `.jad`/`.jar` pair from the memory card, or over the air from `/ota/Snake.jad`. |

## Stealth mode

The app installs as **Snake** (vendor "Mobile Apps") and opens on a playable Snake game.
This is a cosmetic disguise, not a security boundary (real auth is the per-device key).

- Unlock: on the Snake screen type `8 9 1 4 5 6 1 9 1 1 1`. The sequence is derived from the
  level table `LVL_TAB` in `SnakeCanvas.java`; change that table to change the code (it also drives game speed).
- Panic: hold `0` on any screen to jump back to the game and drop in-flight requests;
  `*` on the home screen does the same but keeps the unsent draft.
