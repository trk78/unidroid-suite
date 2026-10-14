#!/system/bin/sh
# UniDroid Remote - boot service (run by Magisk at late_start).
MODDIR=${MODDIR:-$(cd "$(dirname "$0")" && pwd)}
PORT=${UDR_PORT:-8080}

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 4

# One long-lived process injects keys directly (no JVM start per key press).
# Respawn it if it ever exits.
while true; do
  CLASSPATH="$MODDIR/unidroid-server.jar" app_process /system/bin --nice-name=unidroid-remote \
    com.unidroid.remote.Server "$PORT" "$MODDIR/webroot" >"$MODDIR/server.log" 2>&1
  sleep 3
done
