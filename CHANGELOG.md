# Changelog

## v0.2.0 - simple menu, no disguise

- **Removed the Snake disguise.** The app now installs as "VibeNokia" (`VibeNokia.jar/.jad`, class `VibeNokiaMIDlet`),
  opens straight into the main menu, and has no key-sequence unlock, panic key or hidden terminal.
  The server's OTA paths changed from `/ota/Snake.*` to `/ota/VibeNokia.*`. Existing phones that installed the old
  "Snake" build keep it as a separate app; install the new one and delete the old icon.
- **Simple menu by default:** Claude chat + Settings. New Settings > Features screen with switches for Telegram,
  OpenCode, Sessions, Voice messages and Advanced (servers, update, key rotation, re-pairing).
  Saved in the phone's RMS; an upgrade from an older build keeps everything it already showed.
- **Server-side ceiling:** `VIBENOKIA_FEATURES` (default: all) hides features in the app and turns off the Telegram and
  voice API routes (404). The list is sent to the phone in `/ping`.
- Home tiles are numbered by position; microphone permission is requested only when voice is enabled.
- Client version 2.7.0.

## v0.1.0 - first public release

- J2ME client (Claude / Telegram / voice) and FastAPI server with per-device HMAC auth.
- Server binds to `127.0.0.1` by default; Docker Compose and `scripts/quickstart.sh`.
- 41-game catalogue (9 own, 32 MIT from agneay/j2me-100-games).
