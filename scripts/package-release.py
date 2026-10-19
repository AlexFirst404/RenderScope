"""Package the verified local release and actual publication media."""
import hashlib
import json
from pathlib import Path
import re
import zipfile
from PIL import Image

root = Path(__file__).resolve().parent.parent
distribution = root / "distribution"
verification = json.loads((distribution / "verification.json").read_text(encoding="utf-8"))
artifact = distribution / verification["artifact"]
assert hashlib.sha256(artifact.read_bytes()).hexdigest() == verification["sha256"]

brand = distribution / "brand"
with Image.open(brand / "renderscope-original.png") as image:
    original = image.convert("RGBA")
assert original.size == (24, 24)
for factor in (8, 16, 32):
    size = original.width * factor
    with Image.open(brand / f"renderscope-logo-{size}x{size}.png") as image:
        actual = image.convert("RGBA")
    expected = original.resize((size, size), Image.Resampling.NEAREST)
    assert actual.tobytes() == expected.tobytes(), f"Pixel/alpha changed in {size}px logo"
for size, factor in ((512, 16), (1024, 32)):
    with Image.open(brand / f"renderscope-icon-{size}.png") as image:
        actual = image.convert("RGBA")
    scaled = original.resize((24 * factor, 24 * factor), Image.Resampling.NEAREST)
    padding = (size - scaled.width) // 2
    assert actual.size == (size, size)
    assert actual.crop((padding, padding, padding + scaled.width, padding + scaled.height)).tobytes() == scaled.tobytes()
    assert actual.getpixel((0, 0)) == original.getpixel((0, 0))
with Image.open(distribution / "gallery/heatmap-demo.gif") as gif:
    assert gif.n_frames == 16 and gif.size == (1280, 720)

for markdown in [root / "README.md", *root.glob("docs/*.md"), *distribution.glob("*.md")]:
    for link in re.findall(r"\]\(([^)]+)\)", markdown.read_text(encoding="utf-8")):
        if "://" in link or link.startswith("#"):
            continue
        target = link.split("#", 1)[0].strip("<>")
        assert (markdown.parent / target).exists(), f"Broken local link: {markdown.name}: {target}"

(brand / "pixel-integrity.json").write_text(json.dumps({
    "sourceSize": [24, 24],
    "integerScales": [8, 16, 32],
    "rgbaIdenticalToNearestNeighbor": True,
    "paddedPublicationIcons": [512, 1024],
    "padding": "Opaque, matching the original corner color",
}, indent=2) + "\n", encoding="utf-8")

destination = distribution / "renderscope-0.1.0-publication.zip"
files = [path for path in distribution.rglob("*") if path.is_file() and path.suffix.lower() != ".zip"]
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(files):
        archive.write(path, path.relative_to(distribution).as_posix())
with zipfile.ZipFile(destination) as archive:
    assert archive.testzip() is None
    assert archive.read(artifact.name) == artifact.read_bytes()
    assert "gallery/heatmap-demo.gif" in archive.namelist()
print(f"{destination.name}: {len(files)} files, {destination.stat().st_size:,} bytes")
print("Verified original RGBA pixels, padded icons, GIF frames, links and archived jar.")
