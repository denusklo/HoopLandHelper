#!/usr/bin/env bash
# Fetch the frida-inject binary the app bundles in assets (gitignored, ~53MB).
# Run from the repo root after cloning, before ./gradlew assembleDebug.
# Keep VER in sync with FRIDA_VER in FridaAimAssist.kt.
set -euo pipefail
VER=17.15.5
DEST=app/src/main/assets/frida/frida-inject
mkdir -p "$(dirname "$DEST")"
URL="https://github.com/frida/frida/releases/download/${VER}/frida-inject-${VER}-android-arm64.xz"
echo "fetching frida-inject ${VER} (arm64)..."
curl -fsSL "$URL" | xz -d > "$DEST"
echo "wrote $DEST ($(du -h "$DEST" | cut -f1))"
