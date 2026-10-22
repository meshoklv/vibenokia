# Contributing

Issues and PRs are welcome. Keep changes small and tested:

- Server: `python3 -m py_compile server/nokia_api.py`; exercise the flow with `tools/phone_emu.py` (see its header).
- Client: build with `make -C client` (Docker); J2ME is CLDC 1.1, Java 1.3 syntax, no `List`/`Form`.
- Games: `./scripts/build-games.sh` and `games/tools/verify.py` must pass.
- Never commit keys, tokens, `.env` or `data/`.
