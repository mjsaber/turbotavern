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

# gomobile bind itself shells out to `gobind` (exec.LookPath). To avoid using
# a stale gobind already on PATH, materialize the pinned one every run and
# prepend its install dir. Then verify the resolved binary really points at
# the pinned x/mobile module version.
GOBIN_DIR="${GOBIN:-$(go env GOPATH)/bin}"
export PATH="$GOBIN_DIR:$PATH"
go install golang.org/x/mobile/cmd/gobind

PINNED_XMOBILE=$(grep -E '^[[:space:]]*golang\.org/x/mobile' go.mod | awk '{print $2}' | head -1)
GOBIND_PATH="$(command -v gobind)"
RESOLVED=$(go version -m "$GOBIND_PATH" 2>/dev/null | awk '$1=="mod" && $2=="golang.org/x/mobile" {print $3}')
if [[ "$RESOLVED" != "$PINNED_XMOBILE" ]]; then
    echo "gobind version mismatch: $GOBIND_PATH -> $RESOLVED (expected $PINNED_XMOBILE)" >&2
    exit 1
fi
echo "gobind: $GOBIND_PATH ($RESOLVED)"

OUT_DIR="../overlay-app/app/libs"
mkdir -p "$OUT_DIR"

$GOMOBILE bind \
    -target=android/arm64 \
    -androidapi 29 \
    -javapkg com.bobassist.gomobile \
    -tags="cmfa with_gvisor" \
    -ldflags="-s -w" \
    -trimpath \
    -o "$OUT_DIR/bobcore.aar" \
    .

echo "Built: $OUT_DIR/bobcore.aar"
ls -lh "$OUT_DIR/bobcore.aar"
