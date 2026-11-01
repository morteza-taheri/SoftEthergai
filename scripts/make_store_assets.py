#!/usr/bin/env python3
"""Regenerate the store artwork in `misc/` from the CURRENT app icon.

BLOCK v1.0.79. `misc/` still carried the previous brand — a teal shield with a
white star and a padlock — in every store asset: the 512 px listing icon, the
"Pro" variant, the iOS icon and both feature graphics. The app itself has used
the navy shield / keyhole / lightning mark for a long time, so the store listing
was advertising a product that no longer exists.

Rather than hand-editing four bitmaps, this script composites them from the one
source of truth that already ships in the APK:

    app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png   (108 dp layer)
    app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png   (108 dp layer)

so the store icon and the launcher icon can never drift apart again. Re-run it
whenever the icon artwork changes:

    python scripts/make_store_assets.py

The two `.psd` files in `misc/` are the ORIGINAL layered sources for the old
brand; they cannot be regenerated and are deliberately left untouched (git keeps
them, and they document where the old artwork came from).
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res" / "mipmap-xxxhdpi"
MISC = ROOT / "misc"

BG_SRC = RES / "ic_launcher_background.png"
FG_SRC = RES / "ic_launcher_foreground.png"

FONT_BOLD = Path("C:/Windows/Fonts/arialbd.ttf")
FONT_REGULAR = Path("C:/Windows/Fonts/arial.ttf")

# Feature-graphic strings, kept identical to the artwork they replace.
FEATURE_TEXT = {
    "feature-graphic_en.png": "Free VPN for every one",
    "feature-graphic_vipng.png": "VPN miễn phí cho mọi người",
}


def load_layers() -> tuple[Image.Image, Image.Image]:
    if not BG_SRC.is_file() or not FG_SRC.is_file():
        sys.exit(f"missing icon layers under {RES}")
    bg = Image.open(BG_SRC).convert("RGBA")
    fg = Image.open(FG_SRC).convert("RGBA")
    if bg.size != fg.size:
        fg = fg.resize(bg.size, Image.LANCZOS)
    return bg, fg


def composite(size: int, bg: Image.Image, fg: Image.Image) -> Image.Image:
    """The adaptive icon as one square bitmap (what a launcher shows)."""
    base = bg.resize((size, size), Image.LANCZOS)
    base.alpha_composite(fg.resize((size, size), Image.LANCZOS))
    return base


def _rounded_mask(size: int, radius_pct: float = 0.22, circle: bool = False) -> Image.Image:
    """An anti-aliased rounded-square (or circular) alpha mask."""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    if circle:
        draw.ellipse([0, 0, size - 1, size - 1], fill=255)
    else:
        draw.rounded_rectangle(
            [0, 0, size - 1, size - 1], radius=int(size * radius_pct), fill=255
        )
    return mask


def masked_icon(size: int, bg: Image.Image, fg: Image.Image,
                radius_pct: float = 0.22, circle: bool = False) -> Image.Image:
    """The adaptive icon as a rounded-square (or round) bitmap on TRANSPARENCY.

    BLOCK v1.0.79. The legacy `mipmap-*/ic_launcher.png` shipped as a fully
    opaque square — every corner pixel was solid navy (27, 36, 64, 255) — so on
    Android 7 (which has no adaptive-icon mask) the launcher showed a hard dark
    square instead of the rounded card every Android 8+ device shows. The
    artwork is identical; only the corners are cut now.
    """
    hi = size * 4  # supersample, then downsample: clean edges at every density
    base = composite(hi, bg, fg)
    mask = _rounded_mask(hi, radius_pct, circle)
    base.putalpha(ImageChops.multiply(base.getchannel("A"), mask))
    return base.resize((size, size), Image.LANCZOS)


def store_icon(bg: Image.Image, fg: Image.Image, size: int = 512) -> Image.Image:
    """Play Store listing icon: full-bleed, 32-bit PNG."""
    return composite(size, bg, fg)


def pro_icon(bg: Image.Image, fg: Image.Image, size: int = 512) -> Image.Image:
    """Same icon with the red 'Pro' badge the old asset carried."""
    icon = composite(size * 2, bg, fg)
    layer = Image.new("RGBA", icon.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    font = ImageFont.truetype(str(FONT_BOLD), int(size * 0.30))
    text = "PRO"
    box = draw.textbbox((0, 0), text, font=font)
    tw, th = box[2] - box[0], box[3] - box[1]
    pad_x, pad_y = int(size * 0.10), int(size * 0.05)
    badge = Image.new("RGBA", (tw + pad_x * 2, th + pad_y * 2), (0, 0, 0, 0))
    bd = ImageDraw.Draw(badge)
    bd.rounded_rectangle(
        [0, 0, badge.size[0] - 1, badge.size[1] - 1],
        radius=int(size * 0.06),
        fill=(220, 38, 38, 255),
    )
    bd.text((pad_x, pad_y - box[1]), text, font=font, fill=(255, 255, 255, 255))
    badge = badge.rotate(-18, expand=True, resample=Image.BICUBIC)
    # Bottom-right, inside the icon.
    pos = (icon.size[0] - badge.size[0] - int(size * 0.10),
           icon.size[1] - badge.size[1] - int(size * 0.10))
    layer.alpha_composite(badge, pos)
    icon.alpha_composite(layer)
    return icon.resize((size, size), Image.LANCZOS)


def ios_icon(bg: Image.Image, fg: Image.Image, size: int = 1024) -> Image.Image:
    """App Store icon: iOS rejects alpha, so flatten onto the background."""
    icon = composite(size, bg, fg)
    flat = Image.new("RGB", (size, size), (10, 18, 33))
    flat.paste(icon, (0, 0), icon)
    return flat


def feature_graphic(bg: Image.Image, fg: Image.Image, text: str,
                    size: tuple[int, int] = (1024, 500)) -> Image.Image:
    """1024x500 banner: the brand gradient, the icon on the left, the tagline right."""
    w, h = size
    canvas = bg.resize(size, Image.LANCZOS).convert("RGBA")

    # ONLY the foreground layer: the background layer is a full-bleed square, so
    # compositing it here leaves a visible square seam where its own gradient
    # stops (that was the artifact in the first two renders). The foreground is
    # the rounded card plus its glow on transparency, which sits naturally on the
    # banner gradient.
    icon_px = int(h * 0.92)
    icon = fg.resize((icon_px, icon_px), Image.LANCZOS)

    # No drop shadow: the icon's own background layer is fully opaque, so any
    # shadow derived from its alpha would be a hard-edged dark SQUARE (that is
    # exactly what the first render produced). The gradient already separates the
    # icon from the background.
    icon_x = int(w * 0.03)
    icon_y = (h - icon_px) // 2
    canvas.alpha_composite(icon, (icon_x, icon_y))

    draw = ImageDraw.Draw(canvas)
    font_path = FONT_BOLD if FONT_BOLD.is_file() else FONT_REGULAR
    # The card occupies 21%..79% of the foreground canvas, so the text starts at
    # the CARD's right edge, not at the (mostly transparent) canvas edge — the
    # first layout left ~150 px of dead space there and the tagline had to shrink.
    text_x = icon_x + int(icon_px * 0.79) + int(w * 0.035)
    max_width = w - text_x - int(w * 0.04)

    # Shrink the type until the tagline fits on one line.
    font_size = int(h * 0.20)
    while font_size > 18:
        font = ImageFont.truetype(str(font_path), font_size)
        if draw.textlength(text, font=font) <= max_width:
            break
        font_size -= 2
    font = ImageFont.truetype(str(font_path), font_size)

    box = draw.textbbox((0, 0), text, font=font)
    text_y = (h - (box[3] - box[1])) // 2 - box[1]
    # Shadow first, then the glyphs.
    draw.text((text_x + 3, text_y + 3), text, font=font, fill=(0, 0, 0, 110))
    draw.text((text_x, text_y), text, font=font, fill=(235, 246, 255, 255))
    return canvas.convert("RGB")


def main() -> None:
    bg, fg = load_layers()
    MISC.mkdir(exist_ok=True)

    targets = {
        "ic_laucher.png": store_icon(bg, fg),
        "ic_laucher_pro.png": pro_icon(bg, fg),
        "ic_laucher_ios.png": ios_icon(bg, fg),
    }
    for name, img in targets.items():
        out = MISC / name
        img.save(out, "PNG", optimize=True)
        print(f"wrote {out.relative_to(ROOT)}  {img.size[0]}x{img.size[1]} {img.mode}")

    # Legacy launcher icons (Android 7 / API 24-25 has no adaptive mask).
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, px in densities.items():
        square = masked_icon(px, bg, fg)
        out = RES.parent / f"mipmap-{density}" / "ic_launcher.png"
        square.save(out, "PNG", optimize=True)
        print(f"wrote {out.relative_to(ROOT)}  {px}x{px} rounded")
        round_icon = masked_icon(px, bg, fg, circle=True)
        out = RES.parent / f"mipmap-{density}" / "ic_launcher_round.png"
        round_icon.save(out, "PNG", optimize=True)
        print(f"wrote {out.relative_to(ROOT)}  {px}x{px} circle")

    # In-app logos had the same opaque square; same fix, one size.
    for name in ("ic_app_logo.png", "splash_logo.png"):
        out = RES.parent / "drawable" / name
        masked_icon(432, bg, fg).save(out, "PNG", optimize=True)
        print(f"wrote {out.relative_to(ROOT)}  432x432 rounded")

    for name, text in FEATURE_TEXT.items():
        img = feature_graphic(bg, fg, text)
        out = MISC / name
        img.save(out, "PNG", optimize=True)
        print(f"wrote {out.relative_to(ROOT)}  {img.size[0]}x{img.size[1]} {img.mode}")


if __name__ == "__main__":
    main()
