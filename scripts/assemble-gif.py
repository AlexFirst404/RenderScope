"""Assemble actual smoke-client screenshot frames; never generate profiler data or UI."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parent.parent
gallery = root / "distribution" / "gallery"
paths = sorted(gallery.glob("heatmap-frame-*.png"))
if len(paths) < 2:
    raise SystemExit("Run runSmokeClient first to record real gameplay frames.")
frames = []
for path in paths:
    with Image.open(path) as image:
        frames.append(image.convert("RGB").quantize(colors=256, method=Image.Quantize.MEDIANCUT))
destination = gallery / "heatmap-demo.gif"
frames[0].save(destination, save_all=True, append_images=frames[1:], duration=150,
               loop=0, optimize=False, disposal=2)
with Image.open(destination) as image:
    assert image.n_frames == len(frames), "GIF dropped gameplay frames"
    assert image.size == frames[0].size
print(f"{destination.name}: {len(frames)} actual gameplay frames, {destination.stat().st_size:,} bytes")
