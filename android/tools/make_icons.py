"""
Turns one picture (branding/icon.png or .jpg) into every app-icon file Android needs.
Runs in the GitHub build before Gradle, so changing the picture in GitHub changes the app icon
in the next build. Optional settings in branding/icon.json:
  {"background": "#F3E7CF",   # colour around the picture (default: taken from the picture's edges)
   "zoom": 1.0}               # >1 = picture bigger (more cropped by round icons), <1 = smaller

    python android/tools/make_icons.py branding android/app/src/main/res
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def load(branding):
    for name in ("icon.png", "icon.jpg", "icon.jpeg", "icon.webp"):
        p = branding / name
        if p.exists():
            return ImageOps.exif_transpose(Image.open(p)).convert("RGBA"), p
    raise SystemExit(f"no icon picture in {branding} (expected icon.png / icon.jpg)")


def edge_colour(img):
    """Median colour of the picture's border, so the padding blends in."""
    w, h = img.size
    px = [img.getpixel((x, y)) for x in range(0, w, max(1, w // 64)) for y in (0, h - 1)]
    px += [img.getpixel((x, y)) for y in range(0, h, max(1, h // 64)) for x in (0, w - 1)]
    px = [p for p in px if p[3] > 200] or [(255, 255, 255, 255)]
    return tuple(sorted(c[i] for c in px)[len(px) // 2] for i in range(3)) + (255,)


def square(img, bg):
    """Pads a non-square picture to a square with the background colour (nothing is cut off)."""
    w, h = img.size
    s = max(w, h)
    out = Image.new("RGBA", (s, s), bg)
    out.alpha_composite(img, ((s - w) // 2, (s - h) // 2))
    return out


def main(branding, res):
    branding, res = Path(branding), Path(res)
    img, src = load(branding)
    cfg = json.loads((branding / "icon.json").read_text()) if (branding / "icon.json").exists() else {}
    bg = cfg.get("background")
    bg = tuple(int(bg.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)) + (255,) if bg else edge_colour(img)
    zoom = float(cfg.get("zoom", 1.0))
    pic = square(img, bg)
    hexbg = "#%02X%02X%02X" % bg[:3]

    for d, k in DENSITIES.items():
        folder = res / f"mipmap-{d}"
        folder.mkdir(parents=True, exist_ok=True)
        # Adaptive icon (Android 8+): launchers show the middle 72 of 108 units, often as a circle.
        # The picture fills ~72 units * zoom, so it is fully visible in a rounded square.
        n = round(108 * k)
        fg = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        side = max(1, round(72 * k * zoom))
        p = pic.resize((side, side), Image.LANCZOS)
        if side <= n:
            fg.alpha_composite(p, ((n - side) // 2, (n - side) // 2))
        else:                                   # zoomed past the edges: keep the middle
            o = (side - n) // 2
            fg.alpha_composite(p.crop((o, o, o + n, o + n)))
        fg.save(folder / "ic_launcher_foreground.png")
        # Old-style icons (also used by some launchers and settings screens)
        m = round(48 * k)
        legacy = pic.resize((m, m), Image.LANCZOS)
        legacy.convert("RGB").save(folder / "ic_launcher.png")
        mask = Image.new("L", (m * 4, m * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, m * 4 - 1, m * 4 - 1), fill=255)
        round_icon = Image.new("RGBA", (m, m), (0, 0, 0, 0))
        round_icon.paste(legacy, (0, 0), mask.resize((m, m), Image.LANCZOS))
        round_icon.save(folder / "ic_launcher_round.png")

    (res / "values").mkdir(parents=True, exist_ok=True)
    (res / "values" / "ic_launcher_background.xml").write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">{hexbg}</color>\n</resources>\n')
    any26 = res / "mipmap-anydpi-v26"
    any26.mkdir(parents=True, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_background" />\n'
           '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    (any26 / "ic_launcher.xml").write_text(xml)
    (any26 / "ic_launcher_round.xml").write_text(xml)
    print(f"icons made from {src.name} ({img.size[0]}x{img.size[1]}), background {hexbg}, zoom {zoom}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
