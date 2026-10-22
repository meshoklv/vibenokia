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
| Side-loading | Via a `.jad`/`.jar` pair from the memory card, or over the air from `/ota/VibeNokia.jad`. |

## UI model

- The home screen is a stack of tiles (`HomeCanvas`). Simple mode shows *Claude* and *Settings*;
  *Telegram*, *OpenCode* and *Sessions* are added by the switches in Settings > Features.
- Feature switches live in `Features.java` (bit mask, RMS store `features`). A feature is visible only if
  the user enabled it **and** the server allows it (`VIBENOKIA_FEATURES`, delivered in the `/ping` reply).
- Digits open tiles by their position on screen (1, 2, 3, 4, 6); `5`/OK opens the selected tile.
