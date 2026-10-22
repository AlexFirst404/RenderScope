"""Download checksum-verified optional renderer test profiles. Never launches Minecraft/Gradle.

Profiles stay isolated: Embeddium and the real Rubidium JAR are never combined.
The installed Forge launcher accepts the generated --extra-mod-manifest.
Fabric manifests are inputs for the Fabric launcher, not Forge's mods folder.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / ".tools/perf-compat/profiles"
PINS = {
    "embeddium": ("UTbfe5d1", "sk9rgfiA", "0.3.31+mc1.20.1"),
    "rubidium": ("VKligEsm", "4ZqxOvjD", "0.7.1"),
    "oculus": ("iQ1SwGc3", "GchcoXML", "1.8.0"),
    "mekanism_extras": ("9T6CetkG", "HUZkxNTQ", "1.5.0"),
    "sodium": ("OihdIimA", "AANobbMI", "0.5.13+mc1.20.1"),
    "iris": ("s5eFLITc", "YL57xq9U", "1.7.6+mc1.20.1"),
}
PROFILES = {
    "forge-embeddium": ["embeddium", "mekanism_extras"],
    "forge-rubidium": ["rubidium", "mekanism_extras"],
    "forge-rubidium-vanilla": ["rubidium"],
    "forge-oculus": ["embeddium", "oculus", "mekanism_extras"],
    "forge-mekanism-extras": ["mekanism_extras"],
    "fabric-sodium": ["sodium"],
    "fabric-iris": ["sodium", "iris"],
}


def download(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "RenderScope compatibility audit/0.3"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read()
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def stage(version_id: str, project_id: str, destination: Path, runtime_id: str, runtime_version: str) -> dict:
    metadata_url = f"https://api.modrinth.com/v2/version/{version_id}"
    metadata_cache = OUTPUT.parent / ("version-" + version_id + ".json")
    if not metadata_cache.is_file():
        metadata_cache.write_bytes(download(metadata_url))
    metadata = json.loads(metadata_cache.read_bytes())
    if metadata["project_id"] != project_id or "1.20.1" not in metadata["game_versions"]:
        raise RuntimeError("Pinned vendor project or Minecraft version mismatch")
    file = next(entry for entry in metadata["files"] if entry.get("primary"))
    if Path(file["filename"]).name != file["filename"]:
        raise RuntimeError("Unexpected vendor filename")
    path = destination / file["filename"]
    cache = ROOT / ".tools/perf-compat" / file["filename"]
    if not cache.is_file() or hashlib.sha512(cache.read_bytes()).hexdigest() != file["hashes"]["sha512"]:
        cache.write_bytes(download(file["url"]))
    data = cache.read_bytes()
    if hashlib.sha512(data).hexdigest() != file["hashes"]["sha512"]:
        raise RuntimeError("Vendor file SHA-512 mismatch: " + file["filename"])
    shutil.copyfile(cache, path)
    return {"id": runtime_id, "version": runtime_version, "file": file["filename"],
            "projectId": project_id, "versionId": version_id, "metadataUrl": metadata_url,
            "url": file["url"], "sha1": file["hashes"]["sha1"], "sha512": file["hashes"]["sha512"],
            "sha256": hashlib.sha256(data).hexdigest()}


def prepare(name: str) -> Path:
    folder = OUTPUT / name
    folder.mkdir(parents=True, exist_ok=True)
    ids = PROFILES[name]
    assert not ("embeddium" in ids and "rubidium" in ids)
    manifest = {"profile": name, "minecraftVersion": "1.20.1", "loader": name.split("-")[0],
                "runtimeTested": False, "mods": [stage(*PINS[mod][:2], folder, mod, PINS[mod][2]) for mod in ids]}
    if name in {"forge-oculus", "fabric-iris"}:
        manifest["shaderPack"] = stage("71Hn3myZ", "HVnmMxH1", folder, "complementary-reimagined", "r5.3")
        manifest["shaderPackNote"] = "Pinned real shader pack for active shadow-pass validation, not just shaders-disabled startup"
    if name == "fabric-iris":
        manifest["dependencyNote"] = "Iris Modrinth metadata names Sodium0.5.12-beta.2; bundled fabric.mod.json accepts0.5.x. This profile selects stable0.5.13 and still requires runtime validation."
    target = folder / "mods.json"
    target.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=[*PROFILES, "all"], default="all")
    args = parser.parse_args()
    for profile in PROFILES if args.profile == "all" else [args.profile]:
        print(prepare(profile))


if __name__ == "__main__":
    main()
