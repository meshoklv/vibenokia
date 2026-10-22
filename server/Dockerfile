# VibeNokia server: Python API + Claude Code CLI + ffmpeg (voice). Runs as a non-root user.
FROM node:22-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv ffmpeg ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g @anthropic-ai/claude-code \
 && npm cache clean --force

WORKDIR /app
COPY requirements.txt .
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir -r requirements.txt
COPY nokia_api.py .

# unprivileged user; /home/vibe keeps Claude Code's login state (mounted as a volume)
RUN useradd -m -u 10001 vibe && mkdir -p /app/data && chown -R vibe /app/data
USER vibe
ENV HOME=/home/vibe \
    VIBENOKIA_HOST=0.0.0.0 \
    VIBENOKIA_PORT=8085 \
    VIBENOKIA_DATA=/app/data \
    VIBENOKIA_OTA_DIR=/app/ota \
    VIBENOKIA_GAMES_DIR=/app/games

EXPOSE 8085
CMD ["/opt/venv/bin/python", "nokia_api.py"]
