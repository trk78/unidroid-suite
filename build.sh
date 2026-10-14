#!/usr/bin/env bash
# UniDroid Remote - builds the on-device server (module/unidroid-server.jar).
# Needs a JDK (11+) and the Android SDK (any platform + build-tools).
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "$SDK" ] && [ -n "${LOCALAPPDATA:-}" ] && SDK="$LOCALAPPDATA/Android/Sdk"
[ -z "$SDK" ] && SDK="$HOME/Android/Sdk"

PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
D8="$BUILD_TOOLS/d8"
[ -x "$D8" ] || D8="$BUILD_TOOLS/d8.bat"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "==> javac ($(basename "$PLATFORM"))"
javac --release 11 -Xlint:-options -cp "$PLATFORM/android.jar" -d "$OUT/classes" \
  $(find "$DIR/server/src" -name '*.java')

echo "==> d8 ($(basename "$BUILD_TOOLS"))"
"$D8" --release --min-api 21 --lib "$PLATFORM/android.jar" \
  --output "$DIR/module/unidroid-server.jar" $(find "$OUT/classes" -name '*.class')

echo "Built module/unidroid-server.jar"
