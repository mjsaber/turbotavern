#!/usr/bin/env bash
# Builds bobcore.aar via gomobile bind.
# Uses gomobile/gobind pinned in this module's go.mod (`go tool gomobile ...`).
# Toolchain decisions recorded in PINNED-VERSIONS.md.
set -euo pipefail

cd "$(dirname "$0")"

# Pick NDK: prefer ANDROID_NDK_HOME; else newest NDK under $ANDROID_HOME/ndk/
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -z "${ANDROID_HOME:-}" ]]; then
        echo "ANDROID_HOME unset; cannot locate NDK"
        exit 1
    fi
    candidate=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
    if [[ -z "$candidate" || ! -d "$candidate" ]]; then
        echo "No NDK found under $ANDROID_HOME/ndk/"
        exit 1
    fi
    export ANDROID_NDK_HOME="$candidate"
fi
echo "Using NDK: $ANDROID_NDK_HOME"

# Use the pinned tools from go.mod (`tool` directive); avoid PATH gomobile drift.
GOMOBILE="go tool golang.org/x/mobile/cmd/gomobile"
# gomobile bind shells out to `gobind`. Put go/bin on PATH so it can find
# the pinned gobind installed by `go install` / `go tool`.
export PATH="$(go env GOPATH)/bin:$PATH"
if ! command -v gobind >/dev/null; then
    # First-time: materialize gobind so gomobile bind can exec it
    go install golang.org/x/mobile/cmd/gobind
fi

OUT_DIR="../overlay-app/app/libs"
mkdir -p "$OUT_DIR"

$GOMOBILE bind \
    -target=android/arm64 \
    -androidapi 29 \
    -javapkg com.bobassist.gomobile \
    -ldflags="-s -w" \
    -trimpath \
    -o "$OUT_DIR/bobcore.aar" \
    .

echo "Built: $OUT_DIR/bobcore.aar"
ls -lh "$OUT_DIR/bobcore.aar"
