"""Renders the UniDroid Remote icons (PNG) from the same geometry as assets/remote/icon.svg.

    python scripts/make-icons.py        # needs Pillow
"""
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

OUT = Path(__file__).resolve().parent.parent / "assets" / "remote"
S = 2048                      # supersampled canvas (2x the 1024 SVG grid)
K = S / 1024


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def background():
    top, bottom = (0x1B, 0x23, 0x42), (0x04, 0x05, 0x0A)
    vertical = Image.linear_gradient("L").resize((S, S))
    horizontal = vertical.rotate(90).transpose(Image.FLIP_LEFT_RIGHT)
    grad = ImageChops.add(vertical.point(lambda v: v // 2), horizontal.point(lambda v: v // 2))  # (x + y) / 2
    img = Image.composite(Image.new("RGB", (S, S), bottom), Image.new("RGB", (S, S), top), grad)
    glow = Image.new("RGB", (S, S), (0x0A, 0x84, 0xFF))
    full = Image.new("L", (S, S), 0)
    r = 300 * K
    ImageDraw.Draw(full).ellipse([512 * K - r, 560 * K - r, 512 * K + r, 560 * K + r], fill=int(255 * 0.38))
    return Image.composite(glow, img, full.filter(ImageFilter.GaussianBlur(130 * K)))


def mark(img):
    cx, cy, r, w = 512 * K, 540 * K, 270 * K, 92 * K
    ring = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(ring)
    d.arc([cx - r - w / 2, cy - r - w / 2, cx + r + w / 2, cy + r + w / 2], 305 - 360, 235, fill=255, width=int(w))
    for a in (235, 305):  # round caps
        x, y = cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=255)
    stops = [(0x64, 0xD2, 0xFF), (0x0A, 0x84, 0xFF), (0xBF, 0x5A, 0xF2)]
    grad = Image.new("RGB", (S, 1))
    for x in range(S):
        t = min(max((x / K - 230) / (794 - 230), 0), 1)
        grad.putpixel((x, 0), lerp(stops[0], stops[1], t * 2) if t < .5 else lerp(stops[1], stops[2], t * 2 - 1))
    grad = grad.resize((S, S))
    halo = Image.composite(grad, Image.new("RGB", (S, S)), ring.filter(ImageFilter.GaussianBlur(40 * K)))
    img = ImageChops.screen(img, halo.point(lambda v: int(v * 0.45)))
    img = Image.composite(grad, img, ring)
    d = ImageDraw.Draw(img)
    dr = 58 * K
    d.ellipse([cx - dr, 262 * K - dr, cx + dr, 262 * K + dr], fill=(255, 255, 255))
    return img


def rounded(img, radius):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(m)
    return out


def main():
    art = mark(background())
    # Full-bleed square: iOS and Android masks apply their own corners.
    art.resize((180, 180), Image.LANCZOS).save(OUT / "icon-180.png", optimize=True)
    art.resize((512, 512), Image.LANCZOS).save(OUT / "icon-maskable-512.png", optimize=True)
    # Rounded versions for places that show the icon as-is.
    rounded(art, int(228 * K)).resize((192, 192), Image.LANCZOS).save(OUT / "icon-192.png", optimize=True)
    rounded(art, int(228 * K)).resize((512, 512), Image.LANCZOS).save(OUT / "icon-512.png", optimize=True)


if __name__ == "__main__":
    main()
