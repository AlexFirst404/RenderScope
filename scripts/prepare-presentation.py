#!/usr/bin/env python3
"""Round presentation copies and encode footage captured by the real Minecraft harness.

Raw screenshots, evidence reports and pixel-art logo files are read-only inputs.
Requires Pillow. No UI, measurements or intermediate animation frames are generated.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path: Path) -> str:
    return path.resolve().relative_to(ROOT).as_posix()


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def corner_mask(size: tuple[int, int], radius: int) -> Image.Image:
    """Only the alpha edge is antialiased. Source RGB pixels are never resampled."""
    scale = 4
    width, height = size
    mask = Image.new("L", (width * scale, height * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, width * scale - 1, height * scale - 1), radius=radius * scale, fill=255
    )
    return mask.resize(size, Image.Resampling.LANCZOS)


def rounded_still(source: Path, destination: Path, loader: str) -> dict:
    before = sha256(source)
    with Image.open(source) as original:
        original = original.convert("RGBA")
        radius = round(min(original.size) * 0.04)
        result = original.copy()
        mask = corner_mask(result.size, radius)
        result.putalpha(ImageChops.multiply(original.getchannel("A"), mask))
        assert ImageChops.difference(original.convert("RGB"), result.convert("RGB")).getbbox() is None
        assert result.getpixel((0, 0))[3] == 0
        assert result.getpixel((result.width // 2, result.height // 2))[3] == 255
        result.save(destination, optimize=True)
        size = list(result.size)
    assert sha256(source) == before, f"Original evidence changed: {source}"
    return {
        "file": destination.name,
        "source": relative(source),
        "sourceLoader": loader,
        "sourceSha256": before,
        "sha256": sha256(destination),
        "size": size,
        "cornerRadius": radius,
        "transform": "Alpha corner mask only; every original RGB pixel retained; no resizing",
        "transparentCornersVerified": True,
        "originalUnchangedVerified": True,
    }


def motion_gif(directory: Path, destination: Path, evidence: dict) -> dict | None:
    capture_path = directory / "capture.json"
    if not capture_path.is_file():
        return None
    capture = read_json(capture_path)
    assert capture["frameCount"] == 16 and len(capture["frames"]) == 16
    assert capture["flywheelBackend"] != "flywheel:off", "Footage must show actual instancing"
    assert capture["environment"] == evidence["environment"]
    assert capture.get("primaryJarSha256") == evidence.get("primaryJarSha256"), "Motion/evidence JAR hashes differ"
    expected = evidence.get("presentationMotion", {}).get(capture["mechanism"])
    if not expected:
        return None
    assert expected == capture, "Motion capture is from a different harness run"

    sources = [directory / entry["file"] for entry in capture["frames"]]
    hashes = [sha256(path) for path in sources]
    assert len(set(hashes)) >= 12, "Footage does not contain enough distinct real frames"
    frames = []
    for path in sources:
        with Image.open(path) as source:
            frames.append(source.convert("RGB"))
    size = frames[0].size
    assert all(frame.size == size for frame in frames)
    # Derive one palette from all captured frames, avoiding flickering local palettes.
    atlas = Image.new("RGB", (size[0], size[1] * len(frames)))
    for index, frame in enumerate(frames):
        atlas.paste(frame, (0, size[1] * index))
    palette = atlas.quantize(colors=255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    colors = palette.getpalette()[:765]
    palette.putpalette(colors + colors[:3])
    radius = round(min(size) * 0.04)
    alpha = corner_mask(size, radius)
    alpha_bytes = alpha.tobytes()
    encoded = []
    for frame in frames:
        indexed = frame.quantize(palette=palette, dither=Image.Dither.NONE)
        data = bytearray(indexed.tobytes())
        # Index zero is reserved for transparency; opaque pixels use indices 1..255.
        for index, color in enumerate(data):
            data[index] = 0 if alpha_bytes[index] < 128 else (0 if color == 255 else color) + 1
        output = Image.frombytes("P", size, bytes(data))
        output.putpalette([0, 0, 0] + colors)
        output.info["transparency"] = 0
        encoded.append(output)
    duration = capture["playbackFrameDurationMillis"]
    encoded[0].save(destination, save_all=True, append_images=encoded[1:], duration=duration,
                    loop=0, transparency=0, disposal=2, optimize=False)
    # Reopen the encoded artifact and verify transparency on every full-size frame.
    with Image.open(destination) as verify:
        assert verify.n_frames == 16, f"Unexpected encoded GIF frame count: {verify.n_frames}"
        for index in range(verify.n_frames):
            verify.seek(index)
            rgba = verify.convert("RGBA")
            assert rgba.getpixel((0, 0))[3] == 0
            assert rgba.getpixel((size[0] // 2, size[1] // 2))[3] == 255
            assert verify.info["duration"] == duration
    assert [sha256(path) for path in sources] == hashes, "Original motion frames changed"
    return {
        "file": destination.name,
        "sha256": sha256(destination),
        "sourceLoader": capture["loader"],
        "source": relative(directory),
        "primaryJarSha256": capture.get("primaryJarSha256"),
        "flywheelBackend": capture["flywheelBackend"],
        "frameCount": len(frames),
        "distinctSourceFrames": len(set(hashes)),
        "frameDurationMillis": duration,
        "durationMillis": duration * len(frames),
        "size": list(size),
        "cornerRadius": radius,
        "palette": "255 shared opaque colors plus reserved transparent index; no dithering",
        "sourceFrameSha256": hashes,
        "rawFrameAvailability": capture["rawFrameAvailability"],
        "sourceCapture": relative(capture_path),
        "sourceCaptureSha256": sha256(capture_path),
        "captureCompletedAt": capture["completedAt"],
        "transparentCornersVerified": True,
        "originalUnchangedVerified": True,
    }


def prepare(loader: str, require_motion: bool) -> dict:
    base = ROOT / "distribution" / "forge" if loader == "forge" else ROOT / "distribution"
    output = base / "presentation"
    output.mkdir(exist_ok=True, parents=True)
    loader_name = "Forge" if loader == "forge" else "NeoForge"
    manifest = {
        "sourceLoader": loader_name,
        "provenance": "Presentation copies of actual Minecraft screenshots and captured framebuffer frames",
        "pixelArtLogo": "Original and enlarged logo files are untouched; only presentation image outer alpha corners are rounded",
        "stills": [],
        "animations": [],
        "pendingAnimations": [],
    }
    selected = [(base / "brand" / "renderscope-cover-1600x900.png", "cover.png")]
    if loader == "forge":
        evidence_dir = base / "compatibility-installed"
        evidence_path = evidence_dir / "compat-result.json"
        evidence = read_json(evidence_path)
        assert evidence["success"], "Use successful installed compatibility evidence"
        selected.extend((evidence_dir / source, name) for source, name in [
            ("01-create-mekanism-mods.png", "mods.png"),
            ("08-create-instanced-shaft-detail.png", "flywheel-visual-detail.png"),
            ("09-create-instanced-shaft-selected.png", "create-shaft-highlight.png"),
            ("10-create-instanced-motor-selected.png", "create-motor-highlight.png"),
            ("11-create-instanced-press-selected.png", "create-press-highlight.png"),
            ("06-create-mekanism-heatmap.png", "heatmap.png"),
            ("07-compatibility-capture.png", "capture.png"),
        ])
        manifest["sourceEvidenceReport"] = relative(evidence_path)
        manifest["sourceEvidenceReportSha256"] = sha256(evidence_path)
        manifest["primaryJarSha256"] = evidence.get("primaryJarSha256")
        for mechanism in ("shaft", "press"):
            name = f"create-{mechanism}-live.gif"
            movie = motion_gif(output / f"media-frame-{mechanism}", output / name, evidence)
            if movie:
                manifest["animations"].append(movie)
            else:
                manifest["pendingAnimations"].append(name)
        if require_motion:
            assert not manifest["pendingAnimations"], "Run the installed compatibility harness to capture actual motion first"
    else:
        selected.extend((base / "gallery" / source, name) for source, name in [
            ("01-overview.png", "overview.png"),
            ("02-mods.png", "mods.png"),
            ("09-world-heatmap.png", "heatmap.png"),
            ("06-capture.png", "capture.png"),
        ])
    for source, name in selected:
        manifest["stills"].append(rounded_still(source, output / name, loader_name))
    (output / "presentation-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"loader": loader_name, "stills": len(manifest["stills"]), "animations": len(manifest["animations"]),
            "pendingAnimations": manifest["pendingAnimations"], "manifest": relative(output / "presentation-manifest.json")}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", choices=("forge", "neoforge", "all"), default="all")
    parser.add_argument("--require-motion", action="store_true", help="Fail if installed real-game motion footage is missing")
    options = parser.parse_args()
    loaders = ("forge", "neoforge") if options.loader == "all" else (options.loader,)
    for loader in loaders:
        print(json.dumps(prepare(loader, options.require_motion and loader == "forge"), ensure_ascii=False))


if __name__ == "__main__":
    main()
