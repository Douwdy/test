#!/usr/bin/env python3
"""Convertit les captures d'écran de content/captures/ (PNG d'origine) en WebP légers dans public/img/.

    pip install pillow && python3 tools/images.py

Portrait : 360 et 720 px de large. Paysage : 800 et 1600 px de large.
"""
import pathlib
from PIL import Image

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "content" / "captures"
OUT = REPO / "public" / "img"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for f in sorted(SRC.glob("*.png")):
        im = Image.open(f).convert("RGB")
        widths = (800, 1600) if im.width > im.height else (360, 720)
        for w in widths:
            h = round(im.height * w / im.width)
            dest = OUT / f"{f.stem}-{w}.webp"
            im.resize((w, h), Image.LANCZOS).save(dest, "WEBP", quality=82, method=6)
            print(f"écrit public/img/{dest.name} ({w}×{h}, {dest.stat().st_size // 1024} Ko)")


if __name__ == "__main__":
    main()
