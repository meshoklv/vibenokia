<p align="center">
  <img src="docs/cover.png" alt="VibeNokia" width="100%">
</p>

<h1 align="center">VibeNokia</h1>

<p align="center">
  <b>Vibe-code with Claude from a Nokia 3310.</b><br>
  A tiny J2ME terminal on a 2017 feature phone, a small signed-request server behind it, and 41 mini games for the menu.
</p>

<p align="center">
  <img alt="License" src="https://img.shields.io/github/license/meshoklv/vibenokia?style=flat-square">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Nokia%203310%20%C2%B7%20J2ME-blue?style=flat-square">
  <img alt="Server" src="https://img.shields.io/badge/server-Python%203.12%20%C2%B7%20FastAPI-green?style=flat-square">
  <img alt="Docker" src="https://img.shields.io/badge/run-docker%20compose-2496ED?style=flat-square">
  <img alt="PRs welcome" src="https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square">
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> ·
  <a href="#features">Features</a> ·
  <a href="#how-it-works">How it works</a> ·
  <a href="#security">Security</a> ·
  <a href="README.ru.md">Русский</a>
</p>

---

## At a glance

| 📟 A real terminal on 240x320 | 🔐 Signed requests, no secrets in the app | 🎮 41 games included |
|---|---|---|
| Chat with Claude, switch sessions and models, send voice messages, read Telegram, all from a 12-key keypad. | The phone is paired once and gets its own key. Every request is HMAC-signed; the key never crosses the network. | Nine written here, 32 MIT-licensed ones from the J2ME Game Archive, each with its own icon. |

```text
you    ▸ why does my build fail on line 42?
claude ▸ reading build.log ...
claude ▸ Missing semicolon after the
         import on line 41. Add ';'.
you    ▸ (holds *, speaks) "and fix the test too"
```

*Illustrative transcript, not a recording.*

## Why

Because the phone is the point. A feature phone has no browser worth using, no app store, and a battery that lasts a week, yet it can open a plain HTTP connection. VibeNokia puts a thin, hand-written Java ME client on it and does everything heavy on a server you control, so you get a pocketable Claude without a smartphone. It is also a compact reference for a hard target: CLDC 1.1, no crypto library (SHA-256/HMAC are written by hand), custom `Canvas` UI, request signing and replay protection over a channel that cannot do TLS.

## Features

| | |
|---|---|
| 💬 **Chat** | Claude sessions with history, rename/delete, model switch (Opus / Sonnet / Haiku) |
| 🎙 **Voice** | Hold `*` to record AMR; the server transcribes it (Gemini via OpenRouter, Groq Whisper fallback) |
| ✈️ **Telegram** | Contacts, text and voice through your own bot, with an unread badge on the home screen |
| 🔑 **Per-device keys** | One-time pairing code, key rotation from the phone, revoke a device from the server |
| 🛡 **Replay-proof** | HMAC-SHA256 over method, path, counter, body hash and host; 64-request sliding window |
| 🫥 **Stealth mode** | Installs as a Snake game; a key sequence unlocks it, holding `0` hides it instantly |
| 📦 **Over-the-air** | Serves its own `.jad/.jar` for install and updates |
| 🎮 **Game catalogue** | 41 standalone MIDlets, no permissions, no network |
| 🌍 **RU / EN** | Interface in both languages |

## Quick start

**Requirements:** Docker with Compose, `make`, and a Claude API key or a Claude Code login. A public host/port the phone can reach for real use (see [deployment](docs/deployment.md)).

```bash
git clone https://github.com/meshoklv/vibenokia && cd vibenokia
./scripts/quickstart.sh                  # creates .env with a fresh key, builds and starts the server on 127.0.0.1:8085
```

Give it Claude access, either way:

```bash
echo 'ANTHROPIC_API_KEY=sk-ant-...' >> .env && docker compose up -d     # API key
docker compose run --rm server claude login                              # or log in once (state kept in ./data/claude-home)
```

Build the phone app (needs Docker; it pulls a public J2ME toolchain image) and the games:

```bash
make -C client SERVER_URL=http://your.host:8080    # -> client/dist/Snake.jar + Snake.jad
./scripts/build-games.sh                           # optional -> games/dist/
```

Install `Snake.jad` on the phone (copy to the memory card, open it in the file manager), open the "Snake" app, type the unlock sequence from [platform notes](docs/platform-notes.md#stealth-mode), press `5` to request a pairing code, then read it on the server:

```bash
docker compose exec server cat /app/data/pair_code.txt
```

Type the 6 digits on the phone. Done; then set `VIBENOKIA_PAIRING=0` in `.env` and `docker compose up -d`.

<details>
<summary>Run without Docker</summary>

Needs Python 3.10+, `ffmpeg` (voice) and the `claude` CLI on `PATH`.

```bash
./scripts/run-local.sh      # binds to 127.0.0.1:8085
```

</details>

> [!NOTE]
> **Tested on:** the client on a physical Nokia 3310 (2017, TA-1030) by the author during development; in this release the client and all 41 games build in the J2ME container, the server image builds and answers on loopback (403 without auth), and pairing plus signed chat were exercised end to end with the phone emulator and a stub `claude` binary.
> **Not yet tested on:** a real Claude call inside the published Docker image, other Series 30+/40 handsets, TLS fronts with the phone.

## How it works

```mermaid
flowchart LR
  P["Nokia 3310<br/>J2ME client"] -- "plain HTTP<br/>HMAC-signed" --> X["reverse proxy<br/>(your host)"]
  X -- "127.0.0.1:8085" --> S["VibeNokia server<br/>FastAPI + SQLite"]
  S --> C["Claude Code CLI<br/>(-p, no tools by default)"]
  S --> V["Voice: Gemini / Whisper"]
  S --> T["Telegram Bot API"]
```

The phone cannot do modern TLS, so it speaks plain HTTP and proves who it is with a per-device HMAC signature instead. The server verifies the signature and a request counter, then runs `claude -p` with a short system prompt tuned for a tiny screen, and returns text. Voice goes through ffmpeg to a speech-to-text API; Telegram goes through a bot you create. State (sessions, devices, contacts) lives in one SQLite file under `data/`.

## Configuration

Set in `.env` (see [`.env.example`](.env.example)):

| Variable | Default | Meaning |
|---|---|---|
| `VIBENOKIA_API_KEY` | generated | Admin key; protects OTA and game downloads |
| `VIBENOKIA_HOST` / `VIBENOKIA_PORT` | `127.0.0.1` / `8085` | Listen address (Docker publishes on loopback only) |
| `VIBENOKIA_PAIRING` | `0` in code, `1` in `.env.example` | Allow one-time pairing codes |
| `VIBENOKIA_PUBLIC_HOST` | empty | Only accept signatures made for this `host:port` |
| `ANTHROPIC_API_KEY` | empty | Claude access (or `claude login`) |
| `VIBENOKIA_CLAUDE_ARGS` | empty | Extra Claude flags, e.g. `--allowedTools Read,Grep,Glob` |
| `OPENROUTER_API_KEY`, `GROQ_API_KEY` | empty | Voice transcription |
| `TELEGRAM_BOT_TOKEN`, `VIBENOKIA_OWNER_CHAT` | empty | Telegram bridge; send pairing codes to your chat |
| `VIBENOKIA_OPENCODE` | `0` | Optional second engine, read the warning below |

## Security

- The server listens on **loopback only**. Docker publishes `127.0.0.1:8085`. Expose it deliberately through a proxy that forwards only the phone's paths ([deployment](docs/deployment.md)).
- No default passwords. The admin key is generated on first run; devices get their own keys through a one-time code that expires in 10 minutes, is used once, and is never written to the log (it goes to `data/pair_code.txt` with mode 600).
- No network admin API: devices are listed/revoked locally with `tools/devices.py`.
- **No shell by default.** Claude runs with `--permission-mode dontAsk` and no pre-approved tools. If you allow tools via `VIBENOKIA_CLAUDE_ARGS`, a paired phone can make Claude use them, so treat a paired phone like an SSH key and keep the allow-list narrow. The server removes its own secrets from Claude's environment.
- OpenCode (`VIBENOKIA_OPENCODE=1`) is off by default: its agents may run tools per *your* OpenCode configuration.
- ⚠️ The channel is **not encrypted**. Prompts and answers can be read by anyone on the path. Signatures stop forgery, guessing and replay, not eavesdropping.
- Voice and Telegram send data to the services you configure (OpenRouter/Groq/Telegram).

## Roadmap

- [ ] Verify a TLS front on newer Series 40/KaiOS-era handsets
- [ ] Optional per-device tool allow-lists
- [ ] Prebuilt `.jar` release assets

## FAQ

**Will it run on my phone?** It targets CLDC 1.1 / MIDP 2.0 and was built for the Nokia 3310 (2017). Other J2ME phones may work; none are verified.

**Why is it called Snake?** See stealth mode in the [platform notes](docs/platform-notes.md).

**Can I skip Claude?** The server needs the `claude` CLI for chat; Telegram and games work without it.

## Contributing

PRs and issues welcome, see [CONTRIBUTING.md](CONTRIBUTING.md). Some internal comments and in-app strings are intentionally bilingual.

## License

MIT, see [LICENSE](LICENSE). Bundled third-party games: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

<p align="center">Made by <a href="https://github.com/meshoklv">@meshoklv</a></p>
