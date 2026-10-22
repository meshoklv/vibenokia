#!/usr/bin/env bash
# One-command server setup: creates .env with a fresh admin key, builds and starts the container.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || cp .env.example .env
chmod 600 .env

if ! grep -qE '^VIBENOKIA_API_KEY=.+' .env; then
  key="vnk_$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
  sed -i.bak "s/^VIBENOKIA_API_KEY=.*/VIBENOKIA_API_KEY=${key}/" .env && rm -f .env.bak
  echo "Generated VIBENOKIA_API_KEY in .env"
fi
grep -qE '^VIBE_UID=' .env || printf '\nVIBE_UID=%s\nVIBE_GID=%s\n' "$(id -u)" "$(id -g)" >> .env

mkdir -p data/claude-home client/dist games/dist
docker compose up -d --build

echo -n "Waiting for the server"
for _ in $(seq 1 30); do
  if curl -fsS -m 2 -o /dev/null -H "X-Api-Key: $(grep -E '^VIBENOKIA_API_KEY=' .env | cut -d= -f2-)" http://127.0.0.1:8085/ping; then
    echo " ... up on 127.0.0.1:8085"; break
  fi
  echo -n "."; sleep 1
done

cat <<'NEXT'

Next:
  1. Claude access:   add ANTHROPIC_API_KEY to .env  (or: docker compose run --rm server claude login)
  2. Build the phone app:   make -C client SERVER_URL=http://YOUR_PUBLIC_HOST:8085
  3. Put a reverse proxy in front for the phone: docs/deployment.md
  4. Pair the phone:  docker compose exec server cat /app/data/pair_code.txt
Logs: docker compose logs -f server
NEXT
