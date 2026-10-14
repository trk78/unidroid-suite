#!/system/bin/sh
# UniDroid Remote - boot service
# Started by Magisk at late_start (MODDIR is set), or manually (we resolve our own dir).

MODDIR=${MODDIR:-$(cd "$(dirname "$0")" && pwd)}
PORT=${UDR_PORT:-8080}

# Wait for the system to finish booting before serving.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
sleep 4

# Prefer Magisk's BusyBox; fall back to anything on PATH.
BB=/data/adb/magisk/busybox
[ -x "$BB" ] || BB=busybox

exec "$BB" httpd -f -p "$PORT" -h "$MODDIR/webroot"
