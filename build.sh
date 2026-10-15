#!/usr/bin/env bash
# UniDroid Suite - builds build/unidroid.apk (launcher + remote server + web remote) without Gradle.
# Needs a JDK (11+), the Android SDK (platform + build-tools) and Python 3.
#   KEYSTORE=... KEY_ALIAS=... KEY_PASS=... ./build.sh   # sign with your own key
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "$SDK" ] && [ -n "${LOCALAPPDATA:-}" ] && SDK="$LOCALAPPDATA/Android/Sdk"
[ -z "$SDK" ] && SDK="$HOME/Android/Sdk"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
tool() { if [ -x "$BT/$1" ]; then echo "$BT/$1"; elif [ -x "$BT/$1.exe" ]; then echo "$BT/$1.exe"; else echo "$BT/$1.bat"; fi; }
ANDROID_JAR="$PLATFORM/android.jar"

# Debug key by default (its password is the public "android"); never commit a release key.
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-android}"
VERSION_CODE=12
VERSION_NAME=0.4.5

OUT="$DIR/build"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

echo "==> resources"
# targetSdk 28 keeps the Wi-Fi calls (turn on/off, add and join a network) that Android 10+
# removed for apps targeting newer versions: the Wi-Fi panel then works without root.
"$(tool aapt2)" compile --dir "$DIR/res" -o "$OUT/res.zip"
"$(tool aapt2)" link -I "$ANDROID_JAR" --manifest "$DIR/AndroidManifest.xml" \
  --min-sdk-version 21 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$DIR/assets" --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "==> java"
javac --release 11 -Xlint:-options -cp "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$DIR/src" "$OUT/gen" -name '*.java')
"$(tool d8)" --release --min-api 21 --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

echo "==> package"
python - "$OUT/base.apk" "$OUT/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY
"$(tool zipalign)" -p -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$(tool apksigner)" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT/unidroid.apk" "$OUT/aligned.apk"

echo "Built build/unidroid.apk ($(du -k "$OUT/unidroid.apk" | cut -f1) KB)"
