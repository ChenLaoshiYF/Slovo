"""Build geometrically matched Gaussian texture levels from the credited landscape originals."""
from pathlib import Path

from PIL import Image, ImageFilter

resources = Path(__file__).resolve().parents[1] / "app/src/main/res"
destination = resources / "drawable-nodpi"
for name in ("mountain", "sands", "lake", "park", "river", "caucasus"):
    source = (resources / "raw" if name == "park" else destination) / f"land_{name}.webp"
    with Image.open(source) as original:
        photo = original.convert("RGB")
        photo.thumbnail((460, 460), Image.Resampling.LANCZOS)
        for level, sigma in (("soft", 3.2), ("deep", 10)):
            texture = photo.filter(ImageFilter.GaussianBlur(sigma))
            texture.save(destination / f"scene_{level}_{name}.webp", quality=80, method=6)
