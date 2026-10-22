#!/usr/bin/env bash
# Build the game catalogue (games/dist/*.jar|jad) in the J2ME build container.
set -euo pipefail
cd "$(dirname "$0")/../games"
docker run --rm -v "$(pwd):/workspace" ghcr.io/mgruszkiewicz/docker-j2me-build:latest sh build.sh "$@"
python3 tools/verify.py
