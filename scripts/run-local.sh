#!/usr/bin/env bash
# Run the server without Docker (needs python3 >= 3.10, ffmpeg for voice, and the `claude` CLI on PATH).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || cp .env.example .env
if ! grep -qE '^VIBENOKIA_API_KEY=.+' .env; then
  key="vnk_$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
  sed -i.bak "s/^VIBENOKIA_API_KEY=.*/VIBENOKIA_API_KEY=${key}/" .env && rm -f .env.bak
fi
python3 -m venv .venv
.venv/bin/pip install -q -r server/requirements.txt
set -a; . ./.env; set +a
export VIBENOKIA_OTA_DIR="${VIBENOKIA_OTA_DIR:-$PWD/client/dist}" VIBENOKIA_GAMES_DIR="${VIBENOKIA_GAMES_DIR:-$PWD/games/dist}"
exec .venv/bin/python server/nokia_api.py   # binds to 127.0.0.1:8085 unless VIBENOKIA_HOST says otherwise
