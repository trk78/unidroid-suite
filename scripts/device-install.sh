#!/system/bin/sh
# UniDroid Remote - on-device installer (run as root).
# Expects the module files to have been pushed to $SRC first.
SRC=${1:-/data/local/tmp/unidroid-remote}
MOD=/data/adb/modules/unidroid-remote

if [ ! -f "$SRC/module.prop" ]; then
  echo "error: module files not found at $SRC" >&2
  exit 1
fi

rm -rf "$MOD"
mkdir -p "$MOD"
cp -r "$SRC/." "$MOD/"

chmod 0755 "$MOD/service.sh" "$MOD/webroot/cgi-bin/key" "$MOD/webroot/cgi-bin/text"
chmod 0644 "$MOD/module.prop"
# BusyBox httpd serves files; make the web root world-readable.
find "$MOD/webroot" -type f ! -path "*/cgi-bin/*" -exec chmod 0644 {} +

echo "Installed to $MOD (boot-persistent via Magisk)."

# Start now without waiting for a reboot.
MODDIR="$MOD" UDR_PORT="${UDR_PORT:-8080}" setsid sh "$MOD/service.sh" >/dev/null 2>&1 &
echo "Server starting on port ${UDR_PORT:-8080}."
