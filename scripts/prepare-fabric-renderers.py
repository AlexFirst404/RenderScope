"""Prepare an exact stable Fabric Sodium/Iris shader test, with vendor hashes.

No Minecraft client is launched and no user profile is changed. Mod IDs and
runtime versions are read from each downloaded JAR, rather than inferred from filenames.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("renderer_downloads", ROOT / "scripts/prepare-renderer-compat.py")
vendor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vendor)


def latest(project: str, minecraft: str, loader: str | None) -> dict:
    query = {"game_versions": json.dumps([minecraft])}
    if loader:
        query["loaders"] = json.dumps([loader])
    versions = json.loads(vendor.download("https://api.modrinth.com/v2/project/" + project + "/version?" + urllib.parse.urlencode(query)))
    return next(version for version in versions if version["version_type"] == "release")


def stage(version: dict, folder: Path, shader: bool = False) -> dict:
    file = next(item for item in version["files"] if item.get("primary"))
    if Path(file["filename"]).name != file["filename"]:
        raise RuntimeError("Vendor supplied an unsafe filename")
    path = folder / file["filename"]
    if not path.is_file() or hashlib.sha512(path.read_bytes()).hexdigest() != file["hashes"]["sha512"]:
        data = vendor.download(file["url"])
        if hashlib.sha512(data).hexdigest() != file["hashes"]["sha512"]:
            raise RuntimeError("Vendor SHA-512 mismatch: " + file["filename"])
        path.write_bytes(data)
    data = path.read_bytes()
    if shader:
        identity, runtime_version = "complementary-reimagined", version["version_number"]
    else:
        with zipfile.ZipFile(path) as jar:
            metadata = json.loads(jar.read("fabric.mod.json"))
            identity, runtime_version = metadata["id"], metadata["version"]
    return {"id": identity, "version": runtime_version, "file": path.name,
            "projectId": version["project_id"], "versionId": version["id"],
            "metadataUrl": "https://api.modrinth.com/v2/version/" + version["id"], "url": file["url"],
            **file["hashes"], "sha256": hashlib.sha256(data).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    args = parser.parse_args()
    folder = ROOT / ".tools/perf-compat/profiles" / ("fabric-iris-" + args.minecraft)
    folder.mkdir(parents=True, exist_ok=True)
    iris = latest("YL57xq9U", args.minecraft, "fabric")
    pinned = next((dependency["version_id"] for dependency in iris["dependencies"]
                   if dependency.get("project_id") == "AANobbMI" and dependency.get("version_id")
                   and dependency["dependency_type"] == "required"), None)
    sodium = json.loads(vendor.download("https://api.modrinth.com/v2/version/" + pinned)) if pinned else latest("AANobbMI", args.minecraft, "fabric")
    if args.minecraft not in sodium["game_versions"] or "fabric" not in sodium["loaders"]:
        raise RuntimeError("Required Sodium release targets a different Minecraft/loader")
    shader = latest("HVnmMxH1", args.minecraft, None)
    manifest = {"profile": "fabric-iris-" + args.minecraft, "minecraftVersion": args.minecraft, "loader": "fabric",
                "runtimeTested": False, "coveragePolicy": "real-renderer-timing-and-shadow-api; native-geometry-only-if-inspected",
                "mods": [stage(sodium, folder), stage(iris, folder)], "shaderPack": stage(shader, folder, True)}
    target = folder / "mods.json"
    target.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(target.relative_to(ROOT))
    print(json.dumps({"mods": [(entry["id"], entry["version"]) for entry in manifest["mods"]],
                      "shader": manifest["shaderPack"]["version"]}))


if __name__ == "__main__":
    main()
