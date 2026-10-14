#!/usr/bin/env bash
# UniDroid Remote - installer (run on your computer; needs adb + a rooted device).
#
#   ./install.sh                 # single device
#   ./install.sh <serial-or-ip>  # pick a device, e.g. 192.168.1.87:5555
#   UDR_PORT=9000 ./install.sh   # custom port
set -euo pipefail

SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${UDR_PORT:-8080}"

echo "==> Pushing module to device..."
"${ADB[@]}" push "$DIR/module/." /data/local/tmp/unidroid-remote >/dev/null
"${ADB[@]}" push "$DIR/scripts/device-install.sh" /data/local/tmp/udr-install.sh >/dev/null

echo "==> Installing (requires root)..."
"${ADB[@]}" shell "su -c 'UDR_PORT=$PORT sh /data/local/tmp/udr-install.sh /data/local/tmp/unidroid-remote'"

IP="$("${ADB[@]}" shell "ip -4 addr show wlan0 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print \$2}'" | tr -d '\r' || true)"
echo
echo "Done. Open this in any browser on the same Wi-Fi:"
echo "   http://${IP:-<device-ip>}:$PORT"
