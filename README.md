# UniDroid Remote

A self-hosted web remote for **any Android device** — TV boxes, projectors, Fire TV, phones, tablets.
The device serves a small control page over your local network; you drive it from **any browser**
(iPhone, Android, laptop) with a D-pad, media keys and a **full keyboard**. No app, no account, no cloud.

- **Zero client install** — open a URL in Safari/Chrome on the same Wi-Fi.
- **No ads, no telemetry, no internet** — everything stays on your LAN.
- **Keyboard input** — type search queries and text fields from your phone keyboard.
- **Boot-persistent** on rooted devices (Magisk module), or run on demand without root.

## How it works

The device runs a tiny HTTP server (BusyBox `httpd`) that serves the remote page and two CGI
endpoints. Button presses call `input keyevent <KEYCODE>`; typed text calls `input text`.
Both are standard Android commands, so it works on essentially any Android version.

```
browser (phone/PC)  ──HTTP──▶  httpd on device  ──▶  input keyevent / input text
```

## Requirements

**Controlled device (the server):**
- Android with the `input` command (Android 4+) — practically universal.
- A way to run a background process:
  - **Rooted (recommended):** installs as a Magisk module, auto-starts at boot, survives reboots.
  - **Rootless:** start it manually over ADB (or from Termux); no boot auto-start.
- Port `8080` by default (no root needed for the port itself).
- Works out of the box on permissive-SELinux builds; on strict enforcing builds you may need a matching policy.

**Controller (what you hold):** any device with a web browser on the same network.

## Install (rooted, with Magisk)

From a computer with `adb` and the device connected (USB or `adb connect <ip>:5555`):

```bash
git clone https://github.com/<you>/unidroid-remote
cd unidroid-remote
./install.sh            # add the adb serial/ip as an argument if you have several devices
```

Then open `http://<device-ip>:8080` in a browser on the same Wi-Fi.

The installer copies the Magisk module to `/data/adb/modules/unidroid-remote` and starts the
server immediately. It restarts automatically on every boot.

## Install (rootless)

```bash
adb push module/webroot /data/local/tmp/udr
adb shell 'toybox httpd -p 8080 /data/local/tmp/udr' &   # or busybox httpd
```

This runs until the process is killed or the device reboots (no auto-start without root).

## Uninstall

Rooted: remove `/data/adb/modules/unidroid-remote` (or disable it in the Magisk app) and reboot.

## Configuration

- **Port:** set `UDR_PORT` before starting (default `8080`).
- **Keys:** the D-pad and media buttons map to Android `KEYCODE_*` values in `module/webroot/cgi-bin/key`.
  Add your own buttons in `index.html` and they will work as long as the keycode passes the whitelist.

## Security

UniDroid Remote is meant for a **trusted LAN**. By design:

- The `key` endpoint only accepts keycodes matching `[A-Z0-9_]+` (whitelist — no shell injection).
- The `text` endpoint passes the decoded string as a single quoted argument to `input text`
  (no command substitution).
- There is **no authentication** — anyone on your network who can reach the port can control the
  device. **Do not expose port 8080 to the internet.** Keep it behind your router/firewall.

## License

MIT — see [LICENSE](LICENSE).
