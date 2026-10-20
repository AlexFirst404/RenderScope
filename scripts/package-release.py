"""Package the verified local release and actual publication media."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile
from PIL import Image

LICENSE_ID = "LicenseRef-RenderScope-Source-Available-1.0"
LICENSE_NAME = "RenderScope Source-Available License 1.0"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--loader", choices=("forge", "neoforge"), default="forge")
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
properties = dict(re.findall(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\S+)\s*$", (root / "gradle.properties").read_text(), re.MULTILINE))
current_mod_version = properties["mod_version"]
current_minecraft_version = properties["minecraft_version"]
loader = args.loader
loader_name, loader_version = {"forge": ("Forge", "47.4.10"), "neoforge": ("NeoForge", "47.1.106")}[loader]
distribution = root / "distribution" / "forge" if loader == "forge" else root / "distribution"
verification = json.loads((distribution / "verification.json").read_text(encoding="utf-8"))
artifact_pattern = rf"renderscope-{loader}-(.+)\+mc1\.20\.1\.jar"
artifact_match = re.fullmatch(artifact_pattern, verification["artifact"])
assert artifact_match, f"Verification does not describe a bundled {loader_name} artifact"
assert artifact_match.group(1) == current_mod_version, "Verification describes a previous RenderScope release"
assert verification.get("loader", "NeoForge" if loader == "neoforge" else "") == loader_name
assert verification.get("loaderVersion", verification.get(f"{loader}Version")) == loader_version
artifact = distribution / verification["artifact"]
assert hashlib.sha256(artifact.read_bytes()).hexdigest() == verification["sha256"]
release_metadata = json.loads((distribution / "release.json").read_text(encoding="utf-8"))
release = release_metadata["release"]
assert release["loaders"] == [loader], "Publication metadata targets a different loader"
assert release["targetLoaderVersion"] == loader_version
assert release["artifact"] == artifact.name
assert release_metadata["project"]["license"] == LICENSE_ID, "Publication metadata must declare RenderScope's custom source-available license"
license_bytes = (root / "LICENSE").read_bytes()
notices_bytes = (root / "THIRD_PARTY_NOTICES.md").read_bytes()
assert license_bytes.decode("utf-8").splitlines()[0] == LICENSE_NAME
assert f"SPDX-License-Identifier: {LICENSE_ID}" in license_bytes.decode("utf-8")
for folder in (root / "distribution", root / "distribution/forge"):
    assert (folder / "LICENSE").read_bytes() == license_bytes, f"Stale distribution license: {folder.name}"
    assert (folder / "THIRD_PARTY_NOTICES.md").read_bytes() == notices_bytes, f"Stale distribution third-party notices: {folder.name}"
assert verification["license"]["id"] == LICENSE_ID and verification["license"]["bundledTextsMatchSource"] is True
assert verification["license"]["licenseSha256"] == hashlib.sha256(license_bytes).hexdigest()
assert verification["license"]["thirdPartyNoticesSha256"] == hashlib.sha256(notices_bytes).hexdigest()
with zipfile.ZipFile(artifact) as archive:
    for name, expected in (("META-INF/LICENSE", license_bytes), ("META-INF/THIRD_PARTY_NOTICES.md", notices_bytes)):
        assert archive.namelist().count(name) == 1, f"Missing or duplicate bundled legal notice: {name}"
        assert archive.read(name) == expected, f"Bundled legal notice differs from source: {name}"
    metadata = archive.read("META-INF/mods.toml").decode("utf-8")
    match = re.search(r'^\s*license\s*=\s*"([^"\r\n]+)"\s*$', metadata, re.MULTILINE)
    assert match and match.group(1) == LICENSE_ID, "Artifact license metadata is stale or claims another license"

brand = distribution / "brand"
original_path = brand / "renderscope-original.png"
with Image.open(original_path) as image:
    original = image.convert("RGBA")
assert original.size == (24, 24)
integrity = json.loads((brand / "pixel-integrity.json").read_text(encoding="utf-8"))
assert integrity["schemaVersion"] == 2 and integrity["sourceSize"] == [24, 24]
assert integrity["sourceSha256"] == hashlib.sha256(original_path.read_bytes()).hexdigest()
assert integrity["integerScales"] == [8, 16, 32, 64]
assert integrity["publicationIconSizes"] == [768, 1536] and integrity["addedPaddingPixels"] == [0, 0, 0, 0]
assert integrity["rgbaIdenticalToNearestNeighbor"] is True and integrity["nativeSourceBackgroundPreserved"] is True
assert integrity["guiLogoSize"] == [24, 24] and integrity["modListLogoSize"] == [768, 768]
assets = {row["file"]: row for row in integrity["assets"]}
expected_scales = {f"renderscope-logo-{24 * factor}x{24 * factor}.png": factor for factor in (8, 16, 32, 64)}
expected_scales.update({f"renderscope-icon-{size}.png": size // 24 for size in (768, 1536)})
assert set(assets) == set(expected_scales) and len(integrity["assets"]) == len(expected_scales)
for name, metadata in assets.items():
    assert metadata["integerScale"] == expected_scales[name], f"Incorrect scale/filename: {name}"
    size = 24 * metadata["integerScale"]
    assert metadata["size"] == [size, size]
    path = brand / name
    assert metadata["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest(), f"Pixel integrity metadata mismatch: {name}"
    with Image.open(path) as image:
        actual = image.convert("RGBA")
    expected = original.resize((size, size), Image.Resampling.NEAREST)
    assert actual.size == expected.size and actual.tobytes() == expected.tobytes(), f"Pixels/alpha/bounds changed in {name}"
assert not any((brand / f"renderscope-icon-{size}.png").exists() for size in (512, 1024)), "Retired padded icons must not be packaged"
assert (root / "src/main/resources/assets/renderscope/textures/gui/logo.png").read_bytes() == original_path.read_bytes(), "GUI source logo changed"
with zipfile.ZipFile(artifact) as archive:
    with Image.open(io.BytesIO(archive.read("renderscope-logo.png"))) as image:
        mod_list_logo = image.convert("RGBA")
assert mod_list_logo.size == (768, 768) and mod_list_logo.tobytes() == original.resize((768, 768), Image.Resampling.NEAREST).tobytes(), "Mod list logo must be the exact unpadded 32x original"
with Image.open(distribution / "gallery/heatmap-demo.gif") as gif:
    assert gif.n_frames == 16 and gif.size == (1280, 720)

for markdown in [root / "README.md", *root.glob("docs/*.md"), *distribution.glob("*.md")]:
    for link in re.findall(r"\]\(([^)]+)\)", markdown.read_text(encoding="utf-8")):
        if "://" in link or link.startswith("#"):
            continue
        target = link.split("#", 1)[0].strip("<>")
        assert (markdown.parent / target).exists(), f"Broken local link: {markdown.name}: {target}"

version = artifact_match.group(1)
package_name = f"renderscope-forge-{version}-publication.zip" if loader == "forge" else f"renderscope-{version}-publication.zip"
destination = distribution / package_name
files = []
current_numbers = tuple(int(part) for part in current_mod_version.split("."))
for path in distribution.rglob("*"):
    if not path.is_file() or path.suffix.lower() == ".zip" or path.relative_to(distribution).parts[0] in ("forge", "neoforge", "platforms"):
        continue
    relative = path.relative_to(distribution)
    if path.suffix.lower() == ".png" and len(relative.parts) >= 3 and relative.parts[0] == "presentation" and relative.parts[1].startswith("media-frame-"):
        continue  # Source-repository frame proof; keep capture.json and final media in this ZIP.
    if path.suffix.lower() == ".jar" and path != artifact:
        old = re.fullmatch(rf"renderscope-{loader}-(\d+)\.(\d+)\.(\d+)\+mc{re.escape(current_minecraft_version)}\.jar", path.name)
        if path.parent == distribution and old and tuple(int(part) for part in old.groups()) < current_numbers:
            continue  # Preserve historical primary JARs on disk, outside this release ZIP.
        raise AssertionError(f"Unexpected other artifact in {loader_name} materials: {path.name}")
    files.append(path)
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(files):
        archive.write(path, path.relative_to(distribution).as_posix())
with zipfile.ZipFile(destination) as archive:
    assert archive.testzip() is None
    assert archive.read(artifact.name) == artifact.read_bytes()
    assert archive.read("LICENSE") == license_bytes and archive.read("THIRD_PARTY_NOTICES.md") == notices_bytes
    assert "gallery/heatmap-demo.gif" in archive.namelist()
    assert [name for name in archive.namelist() if name.endswith(".jar")] == [artifact.name], "Release archive contains a historical or test JAR"
    assert not any(name.startswith(("forge/", "neoforge/")) for name in archive.namelist()), "Mixed platform materials"
    assert not any(re.match(r"presentation/media-frame-[^/]+/.+\.png$", name, re.IGNORECASE) for name in archive.namelist()), "Raw presentation frames belong in repository proof only"
print(f"{destination.name}: {len(files)} files, {destination.stat().st_size:,} bytes")
print("Verified custom license/notices, original RGBA pixels and full-bounds integer logos/icons, GIF frames, links and archived jar.")
