# Game catalogue (J2ME)

41 standalone MIDlets (CLDC 1.0+/MIDP 2.0, no permissions, no network). Each installs with its
own icon and name. They exist to play, and to keep the phone's menu from showing a lone app.

| Source | Count | Licence |
|---|---|---|
| Own games: `blocks`, `bricks`, `mines`, `x2048`, `boxes`, `invaders`, `pairs`, `pong`, `racer` | 9 | MIT (this repo) |
| [agneay/j2me-100-games](https://github.com/agneay/j2me-100-games) (commit `a81f2c5`) | 32 | MIT, see `third_party/j2me-100-games/LICENSE` |

The 32 upstream games: `keypadchess`, `numberplace`, `fourdrop`, `twentyone`, `klondike`,
`slidefifteen`, `flipdisc`, `keypadcheckers`, `pegjump`, `codebreaker`, `bubbleshot`, `pipeflow`,
`gemswap`, `stardefender`, `coptercave`, `molebonk`, `lasermirrors`, `eightsshed`, `fivestones`,
`switchgrid`, `towerguard`, `rockblaster`, `seastrike`, `minigolf`, `ladderloot`, `drawpoker`,
`pixellogic`, `cellsolitaire`, `cityshield`, `seedsowing`, `roadhopper`, `puckrush`.
Local change: sound is off by default (`Sfx.on = false`). The upstream authors note the
collection was not verified on physical phones. See `../THIRD_PARTY_NOTICES.md`.

## Build

Needs Docker (the J2ME toolchain lives in a public image):

```bash
./scripts/build-games.sh            # all games -> games/dist/*.jar + *.jad, then checks them
./scripts/build-games.sh mines      # one game
```

Built and verified in this repo's test run: 41 of 41 pass `tools/verify.py` (manifest, icon,
MIDlet class present, no permissions requested).

## Install on the phone

- Card: copy `dist/*.jar` and `dist/*.jad` to the memory card, open the `.jad` in the phone's file manager.
- Over the air: set `VIBENOKIA_GAMES=1`, mount `games/dist` (compose does), open `http://<server>/g/<VIBENOKIA_API_KEY>` in the phone browser.

## Add a game

1. `games/src/<id>/` with sources (`package games.<id>;`), `game.conf` (NAME/VENDOR/DESC/CLASS/VERSION), optional `res/`.
2. Add an icon to `tools/icons.py` and run it.
3. Build and run `tools/verify.py`.

Use `Canvas` only (no `List`/`Form`) and test game rules, not just that it launches.
