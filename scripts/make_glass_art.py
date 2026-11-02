#!/usr/bin/env python3
"""
Regenerates Malta TV's artwork in the Liquid Glass style (see .claude/skills/malta-tv-liquid-glass).

Source: art/banner-1536x864.png (the original AI-generated banner). The white TV + Maltese-cross
glyph and the "Malta TV" wordmark are cut out of it as white-on-transparent and re-composed on
glass. Outputs:
  app/src/main/res/drawable-nodpi/splash_logo.png   glyph + wordmark (white, transparent)
  app/src/main/res/mipmap-*/ic_launcher.png          launcher icon (48..192 px)
  app/src/main/res/drawable-xhdpi/banner.png         Android TV banner (640x360)
  art/icon-glass-1024.png, art/banner-glass-1536x864.png  full-size masters

Usage: python3 scripts/make_glass_art.py   (needs Pillow)
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageChops

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"

RED_TOP = (227, 23, 58)      # Malta red, lit
RED_BOTTOM = (110, 0, 20)    # Malta red, deep


def white_mask(img: Image.Image) -> Image.Image:
    """White artwork on a red background -> white with alpha (green channel = whiteness)."""
    g = img.convert("RGB").split()[1]
    a = g.point(lambda v: max(0, min(255, (v - 25) * 255 // 220)))
    out = Image.new("RGBA", img.size, (255, 255, 255, 0))
    out.putalpha(a)
    return out.crop(out.getbbox())


def extract_logo():
    banner = Image.open(ROOT / "art/banner-1536x864.png")
    logo = white_mask(banner.crop((110, 200, 1470, 610)))      # glyph + wordmark
    alpha = logo.split()[3]
    # the glyph ends at the first empty column after x=250
    split = next(x for x in range(250, logo.width)
                 if alpha.crop((x, 0, x + 1, logo.height)).getbbox() is None)
    glyph = logo.crop((0, 0, split, logo.height))
    return logo, glyph.crop(glyph.getbbox())


def backdrop(w, h):
    """Diagonal Malta-red gradient with a soft light bloom top-left (the 'environment')."""
    grad = Image.new("RGB", (w, h))
    px = grad.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.45 + y / h * 0.55)
            px[x, y] = tuple(int(RED_TOP[i] + (RED_BOTTOM[i] - RED_TOP[i]) * t) for i in range(3))
    bloom = Image.new("L", (w, h), 0)
    ImageDraw.Draw(bloom).ellipse((-w * 0.25, -h * 0.35, w * 0.55, h * 0.45), fill=110)
    bloom = bloom.filter(ImageFilter.GaussianBlur(min(w, h) * 0.18))
    return Image.composite(Image.new("RGB", (w, h), (255, 120, 140)), grad, bloom).convert("RGBA")


def glass(base: Image.Image, box, radius):
    """Liquid-glass slab: frosted body, vertical sheen, specular rim, inner rim, soft shadow."""
    w, h = base.size
    x0, y0, x1, y1 = box
    # drop shadow (the slab floats)
    sh = Image.new("L", (w, h), 0)
    off = int((y1 - y0) * 0.04)
    ImageDraw.Draw(sh).rounded_rectangle((x0, y0 + off, x1, y1 + off), radius, fill=120)
    sh = sh.filter(ImageFilter.GaussianBlur(radius * 0.35))
    base = Image.composite(Image.new("RGBA", (w, h), (40, 0, 8, 255)), base, sh)
    # frosted body: blur what's behind the slab and lighten it
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius, fill=255)
    frosted = base.filter(ImageFilter.GaussianBlur(radius * 0.6))
    frosted = Image.blend(frosted, Image.new("RGBA", (w, h), (255, 255, 255, 255)), 0.16)
    base = Image.composite(frosted, base, mask)
    # vertical sheen: brighter at the top edge, fading down
    sheen = Image.new("L", (w, h), 0)
    sd = ImageDraw.Draw(sheen)
    for y in range(y0, y1):
        t = (y - y0) / max(1, (y1 - y0))
        sd.line((x0, y, x1, y), fill=int(70 * (1 - t) ** 2.2 + 8))
    sheen = ImageChops.multiply(sheen, mask)
    base = Image.composite(Image.new("RGBA", (w, h), (255, 255, 255, 255)), base, sheen)
    # specular rim (bright, thicker on top) + inner rim
    stroke = max(2, int(min(x1 - x0, y1 - y0) * 0.008))
    rim = Image.new("L", (w, h), 0)
    ImageDraw.Draw(rim).rounded_rectangle(box, radius, outline=150, width=stroke)
    top = Image.new("L", (w, h), 0)
    ImageDraw.Draw(top).rectangle((x0, y0, x1, y0 + (y1 - y0) * 0.5), fill=255)
    top = top.filter(ImageFilter.GaussianBlur((y1 - y0) * 0.2))
    rim = ImageChops.multiply(rim, top.point(lambda v: 90 + v * 165 // 255))
    base = Image.composite(Image.new("RGBA", (w, h), (255, 255, 255, 255)), base, rim)
    inner = Image.new("L", (w, h), 0)
    ImageDraw.Draw(inner).rounded_rectangle(
        (x0 + stroke * 2, y0 + stroke * 2, x1 - stroke * 2, y1 - stroke * 2),
        max(1, radius - stroke * 2), outline=40, width=max(1, stroke // 2))
    return Image.composite(Image.new("RGBA", (w, h), (255, 255, 255, 255)), base, inner)


def place(base, art, cx, cy, width):
    art = art.resize((int(width), int(art.height * width / art.width)), Image.LANCZOS)
    x, y = int(cx - art.width / 2), int(cy - art.height / 2)
    pad = int(art.width * 0.08) + 8          # room for the blur, so it isn't clipped square
    a = Image.new("L", (art.width + 2 * pad, art.height + 2 * pad), 0)
    a.paste(art.split()[3].point(lambda v: v * 55 // 255), (pad, pad))
    a = a.filter(ImageFilter.GaussianBlur(art.width * 0.012))
    shadow = Image.new("RGBA", a.size, (60, 0, 12, 0))
    shadow.putalpha(a)
    base.alpha_composite(shadow, (x - pad, y - pad + int(art.height * 0.03)))
    base.alpha_composite(art, (x, y))
    return base


def main():
    logo, glyph = extract_logo()
    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    logo.save(RES / "drawable-nodpi/splash_logo.png", optimize=True)

    # Launcher icon
    s = 1024
    icon = backdrop(s, s)
    icon = glass(icon, (150, 150, s - 150, s - 150), 170)
    icon = place(icon, glyph, s / 2, s / 2, 520)
    icon.convert("RGB").save(ROOT / "art/icon-glass-1024.png", optimize=True)
    for folder, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        icon.resize((px, px), Image.LANCZOS).convert("RGB").save(
            RES / f"mipmap-{folder}/ic_launcher.png", optimize=True)

    # TV banner
    bw, bh = 1536, 864
    banner = backdrop(bw, bh)
    banner = glass(banner, (150, 250, bw - 150, bh - 250), 182)
    banner = place(banner, logo, bw / 2, bh / 2, 1000)
    banner.convert("RGB").save(ROOT / "art/banner-glass-1536x864.png", optimize=True)
    banner.resize((640, 360), Image.LANCZOS).convert("RGB").save(
        RES / "drawable-xhdpi/banner.png", optimize=True)
    print("logo", logo.size, "glyph", glyph.size)


if __name__ == "__main__":
    main()
