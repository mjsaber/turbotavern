#!/usr/bin/env bash
# Builds bobcore.aar via gomobile bind.
# Toolchain decision recorded in PINNED-VERSIONS.md.
set -euo pipefail

cd "$(dirname "$0")"

export PATH="$HOME/go/bin:$PATH"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.1.12297006"
fi

if ! command -v gomobile >/dev/null; then
    echo "gomobile not in PATH (looking in $HOME/go/bin)"
    echo "install with: go install golang.org/x/mobile/cmd/gomobile@latest"
    exit 1
fi

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
    echo "NDK not found at $ANDROID_NDK_HOME"
    exit 1
fi

OUT_DIR="../overlay-app/app/libs"
mkdir -p "$OUT_DIR"

gomobile bind \
    -target=android/arm64 \
    -androidapi 24 \
    -javapkg com.bobassist.gomobile \
    -ldflags="-s -w" \
    -trimpath \
    -o "$OUT_DIR/bobcore.aar" \
    .

echo "Built: $OUT_DIR/bobcore.aar"
ls -lh "$OUT_DIR/bobcore.aar"
