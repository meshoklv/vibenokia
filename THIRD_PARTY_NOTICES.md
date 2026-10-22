# Third-party notices

VibeNokia itself is MIT-licensed (see `LICENSE`). The repository bundles source code
from the projects below. Every bundled third-party component was checked for a licence
that permits redistribution; anything without one was left out.

## J2ME Game Archive (32 games)

- Source: <https://github.com/agneay/j2me-100-games>, commit `a81f2c5e0396baac10928ff012bbf9d8a2d787e3`
- Licence: **MIT**, Copyright (c) 2026 Agneay B Nair and the J2ME Game Archive contributors
  (full text: `games/third_party/j2me-100-games/LICENSE`)
- Used: 32 of the 100 games, copied into `games/src/` together with the shared mini-library
  `gamekit` (one copy inside each game):

  `keypadchess`, `numberplace`, `fourdrop`, `twentyone`, `klondike`, `slidefifteen`,
  `flipdisc`, `keypadcheckers`, `pegjump`, `codebreaker`, `bubbleshot`, `pipeflow`,
  `gemswap`, `stardefender`, `coptercave`, `molebonk`, `lasermirrors`, `eightsshed`,
  `fivestones`, `switchgrid`, `towerguard`, `rockblaster`, `seastrike`, `minigolf`,
  `ladderloot`, `drawpoker`, `pixellogic`, `cellsolitaire`, `cityshield`, `seedsowing`,
  `roadhopper`, `puckrush`.
- Local change: `gamekit/Sfx.java` ships with sound off by default (`Sfx.on = false`).
- Note from the upstream authors: the collection has not been verified on physical phones.

## Excluded on purpose

- `maze`, `dungeon`, `jump` (sample games from the book *J2ME Games with MIDP2* by Carol Hamer,
  Apress, 2004): their licence allows personal use only and forbids redistribution, so they are
  **not** part of this repository.

## Build tools (not bundled, not redistributed)

- Docker image `ghcr.io/mgruszkiewicz/docker-j2me-build` (JDK 8 + a J2ME wireless toolkit) is
  pulled by `make` / `scripts/build-games.sh` when *you* build. Its contents are governed by their
  own licences; nothing from it is committed here.
- `@anthropic-ai/claude-code` is installed from npm when *you* build the server image; it is
  subject to Anthropic's terms.
- Python dependencies (FastAPI, Uvicorn, HTTPX, python-multipart) are installed from PyPI under
  their own permissive licences (MIT / BSD).

## Own games (MIT, same as the project)

`blocks`, `bricks`, `mines`, `x2048`, `boxes`, `invaders`, `pairs`, `pong`, `racer`.
