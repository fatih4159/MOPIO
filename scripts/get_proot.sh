#!/usr/bin/env bash
# get_proot.sh — Download a pre-built static aarch64 proot binary from proot-me/proot releases.
# Usage: ./scripts/get_proot.sh
set -euo pipefail

DEST="app/src/main/jniLibs/arm64-v8a/libproot.so"
PROOT_VERSION="${PROOT_VERSION:-5.3.0}"
URL="https://github.com/proot-me/proot/releases/download/v${PROOT_VERSION}/proot-v${PROOT_VERSION}-aarch64-static"

echo "[get_proot] Downloading proot ${PROOT_VERSION} for aarch64…"
echo "  URL: ${URL}"
echo "  DEST: ${DEST}"

mkdir -p "$(dirname "$DEST")"
curl -fsSL --progress-bar -o "$DEST" "$URL"
chmod +x "$DEST"

echo "[get_proot] Done. File size: $(wc -c < "$DEST") bytes"
echo "[get_proot] Verify it's an ELF aarch64 binary:"
file "$DEST" || true
