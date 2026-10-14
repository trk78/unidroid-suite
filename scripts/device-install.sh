#!/system/bin/sh
# UniDroid Remote - on-device installer (run as root).
# Expects the module files to have been pushed to $SRC first.
SRC=${1:-/data/local/tmp/unidroid-remote}
MOD=/data/adb/modules/unidroid-remote

if [ ! -f "$SRC/module.prop" ] || [ ! -f "$SRC/unidroid-server.jar" ]; then
  echo "error: module files (module.prop, unidroid-server.jar) not found at $SRC" >&2
  exit 1
fi

# Stop any running instance: the boot loop first (it would respawn the server), then the server.
# v0.1 ran BusyBox httpd from the module web root; stop that too.
pkill -f "$MOD/service.sh" 2>/dev/null
pkill -f "$MOD/webroot" 2>/dev/null
for pid in $(pidof unidroid-remote); do kill "$pid"; done
sleep 1

rm -rf "$MOD"
mkdir -p "$MOD"
cp -r "$SRC/." "$MOD/"

chmod 0755 "$MOD/service.sh"
chmod 0644 "$MOD/module.prop" "$MOD/unidroid-server.jar"
find "$MOD/webroot" -type d -exec chmod 0755 {} +
find "$MOD/webroot" -type f -exec chmod 0644 {} +

echo "Installed to $MOD (boot-persistent via Magisk)."

# Start now without waiting for a reboot.
MODDIR="$MOD" UDR_PORT="${UDR_PORT:-8080}" setsid sh "$MOD/service.sh" >/dev/null 2>&1 &
echo "Server starting on port ${UDR_PORT:-8080}."
