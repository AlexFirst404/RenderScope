"""Prepare a verified official Fabric production client. Launch only with --launch.

No development Minecraft JARs, launcher accounts or user authentication files are read.
Minecraft versions must exist in Mojang's manifest; Fabric versions must exist in Fabric Meta.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STRICT_GEOMETRY_POLICY = "strict-native-v1"
MODERN_IRIS_POLICY = "iris-sodium-1.21.8-timing-shadow-v1"
spec = importlib.util.spec_from_file_location("verified_client_helpers", ROOT / "scripts/test-installed-client.py")
vendor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vendor)


def qa_geometry_policy(minecraft: str, profile: dict) -> str:
    """A QA expectation for one pinned profile, never a production capability claim."""
    if profile.get("profile") != "fabric-iris-1.21.8":
        return STRICT_GEOMETRY_POLICY
    expected = {entry["id"]: entry["version"] for entry in profile.get("mods", [])}
    shader = profile.get("shaderPack", {})
    if (minecraft != "1.21.8" or profile.get("minecraftVersion") != minecraft
            or profile.get("loader") != "fabric" or len(profile.get("mods", [])) != 2
            or expected != {"sodium": "0.7.2+mc1.21.8", "iris": "1.9.6+mc1.21.8"}
            or shader.get("id") != "complementary-reimagined" or shader.get("version") != "r5.9.3"):
        raise RuntimeError("Partial-geometry QA policy requires its exact Minecraft/mod/shader profile")
    return MODERN_IRIS_POLICY


def request_metadata(url: str) -> bytes:
    for attempt in range(3):
        try:
            return vendor.request_bytes(url)
        except Exception as error:
            if attempt == 2:
                raise RuntimeError("Official metadata request failed: " + url) from error
            time.sleep(attempt + 1)
    raise AssertionError("Unreachable metadata retry")


def json_url(url: str) -> dict:
    return json.loads(request_metadata(url))


def exact_mc(metadata: dict, minecraft: str) -> bool:
    requirement = metadata.get("depends", {}).get("minecraft")
    return isinstance(requirement, str) and requirement.removeprefix("=").strip() == minecraft


def inspect_mod(path: Path, minecraft: str, primary: bool = False) -> dict:
    if not path.is_file():
        raise RuntimeError("Missing Fabric JAR: " + str(path))
    with zipfile.ZipFile(path) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
        if primary and (metadata.get("id") != "renderscope" or not exact_mc(metadata, minecraft)):
            raise RuntimeError("Primary artifact must declare RenderScope and the exact requested Minecraft version")
        if primary and metadata.get("environment") != "client":
            raise RuntimeError("Primary RenderScope artifact must be client-only")
        if primary and any("/smoke/" in name or "compat/CompatTest" in name for name in archive.namelist()):
            raise RuntimeError("Production artifact contains a test harness")
        for config in metadata.get("mixins", []):
            name = config if isinstance(config, str) else config["config"]
            mixin = json.loads(archive.read(name))
            # RenderScope's compiled hooks require their generated AP refmap. Vendor mods
            # may ship directly remapped selectors with an unused legacy refmap name.
            requires_refmap = primary or metadata.get("id", "").startswith("renderscope_")
            if requires_refmap and mixin.get("refmap") and mixin["refmap"] not in archive.namelist():
                raise RuntimeError("Declared production Mixin refmap is missing")
    return metadata


def choose_java(explicit: str | None, minimum: int) -> tuple[Path, int]:
    candidates = [Path(explicit)] if explicit else []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    candidates.extend(sorted((ROOT / ".tools").glob("jdk-*"), reverse=True))
    compatible = []
    for path in candidates:
        if path.is_dir():
            path = path / "bin" / ("java.exe" if platform.system() == "Windows" else "java")
        if path.is_file():
            result = subprocess.run([str(path), "-version"], capture_output=True, text=True)
            match = re.search(r'version "(\d+)', result.stdout + result.stderr)
            if match and int(match[1]) >= minimum:
                if explicit:
                    return path.resolve(), int(match[1])
                compatible.append((int(match[1]), path.resolve()))
        if explicit:
            break
    if compatible:
        major, path = min(compatible, key=lambda entry: entry[0])
        return path, major
    raise RuntimeError(f"Java {minimum}+ is required; pass --java with the installed JDK path")


def library_descriptor(library: dict) -> dict:
    artifact = library.get("downloads", {}).get("artifact")
    if artifact:
        return artifact
    path = vendor.maven_path(library["name"])
    url = library.get("url", "https://libraries.minecraft.net/").rstrip("/") + "/" + path
    sha = library.get("sha1")
    if not sha:
        text = request_metadata(url + ".sha1").decode().strip()
        match = re.match(r"[0-9a-fA-F]{40}", text)
        if not match:
            raise RuntimeError("Official Maven artifact has no valid SHA-1: " + library["name"])
        sha = match[0].lower()
    return {"path": path, "url": url, "sha1": sha}


def prepare_libraries(vanilla: dict, fabric: dict, install: Path) -> tuple[list[Path], Path, list[dict]]:
    selected = vendor.selected_libraries(vanilla, fabric)
    descriptors = []
    for library in selected:
        artifact = library_descriptor(library)
        descriptors.append((library["name"], artifact, False, []))
        classifier = library.get("natives", {}).get("windows")
        if classifier:
            classifier = classifier.replace("${arch}", "64" if sys.maxsize > 2**32 else "32")
            native = library["downloads"]["classifiers"][classifier]
            descriptors.append((library["name"] + ":" + classifier, native, True, library.get("extract", {}).get("exclude", [])))
    def fetch(item: tuple) -> None:
        coordinate, artifact, _, _ = item
        path = artifact["path"]
        caches = vendor.library_caches(path, coordinate) + [ROOT / ".tools/installed-forge-compat/libraries" / path]
        vendor.fetch(artifact["url"], install / "libraries" / path, artifact["sha1"], caches)
    print(f"Preparing {len(descriptors)} checksum-verified client/Fabric libraries.", flush=True)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(fetch, descriptors))
    natives = install / "natives"
    natives.mkdir(parents=True, exist_ok=True)
    suffix = "natives-windows-arm64" if platform.machine().lower() in ("arm64", "aarch64") else "natives-windows" if sys.maxsize > 2**32 else "natives-windows-x86"
    for coordinate, artifact, native, excludes in descriptors:
        if not native and coordinate.split(":")[-1] != suffix:
            continue
        with zipfile.ZipFile(install / "libraries" / artifact["path"]) as archive:
            for member in archive.infolist():
                if member.filename.lower().endswith(".dll") and not any(member.filename.startswith(value) for value in excludes):
                    # Flatten native DLL names; never extract arbitrary vendor archive paths.
                    (natives / Path(member.filename).name).write_bytes(archive.read(member))
    paths = list(dict.fromkeys(install / "libraries" / item[1]["path"] for item in descriptors))
    proof = [{"coordinate": coordinate, "sha1": artifact["sha1"], "file": artifact["path"]} for coordinate, artifact, _, _ in descriptors]
    return paths, natives, proof


def prepare_base(minecraft: str, loader: str, install: Path) -> tuple[dict, dict, Path, list[Path], Path, list[dict]]:
    manifest = json_url(vendor.MANIFEST_URL)
    selected = next((version for version in manifest["versions"] if version["id"] == minecraft), None)
    if selected is None:
        raise RuntimeError("Minecraft version is absent from the official Mojang manifest: " + minecraft)
    path = vendor.fetch(selected["url"], install / "versions" / minecraft / (minecraft + ".json"), selected["sha1"])
    vanilla = vendor.read_json(path)
    if vanilla["id"] != minecraft:
        raise RuntimeError("Mojang version identity mismatch")
    client = vanilla["downloads"]["client"]
    gradle = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches"
    cache = [ROOT / ".tools/installed-forge-compat/versions" / minecraft / (minecraft + ".jar"),
             gradle / "fabric-loom" / minecraft / "minecraft-client.jar",
             gradle / "forge_gradle/minecraft_repo/versions" / minecraft / "client.jar"]
    if os.environ.get("APPDATA"):
        cache.append(Path(os.environ["APPDATA"]) / ".minecraft/versions" / minecraft / (minecraft + ".jar"))
    vendor.fetch(client["url"], path.with_suffix(".jar"), client["sha1"], cache)
    profile_url = f"https://meta.fabricmc.net/v2/versions/loader/{urllib.parse.quote(minecraft, safe='')}/{urllib.parse.quote(loader, safe='')}/profile/json"
    profile_bytes = request_metadata(profile_url)
    fabric = json.loads(profile_bytes)
    if fabric.get("inheritsFrom") != minecraft or fabric.get("mainClass") != "net.fabricmc.loader.impl.launch.knot.KnotClient":
        raise RuntimeError("Unexpected official Fabric production profile")
    expected_coordinate = "net.fabricmc:fabric-loader:" + loader
    if not any(entry["name"] == expected_coordinate for entry in fabric["libraries"]):
        raise RuntimeError("Fabric profile does not contain the requested loader version")
    (install / "fabric-profile.json").write_bytes(profile_bytes)
    classpath, natives, libraries = prepare_libraries(vanilla, fabric, install)
    classpath.append(path.with_suffix(".jar"))
    descriptor = vanilla["assetIndex"]
    assets = None
    for cache in [install / "assets", ROOT / ".tools/installed-forge-compat/assets", gradle / "fabric-loom/assets"]:
        index = cache / "indexes" / (descriptor["id"] + ".json")
        if vendor.valid(index, descriptor["sha1"]):
            objects = vendor.read_json(index)["objects"]
            if all(vendor.valid(cache / "objects" / value["hash"][:2] / value["hash"], value["hash"]) for value in objects.values()):
                assets = cache.resolve()
                print(f"Reusing {len(objects)} verified Minecraft assets.", flush=True)
                break
    if assets is None:
        assets = vendor.prepare_assets(vanilla, install)
    return vanilla, fabric, assets, classpath, natives, libraries


def api_mod(minecraft: str, cache: Path) -> tuple[Path, dict]:
    query = urllib.parse.urlencode({"game_versions": json.dumps([minecraft]), "loaders": '["fabric"]'})
    versions = json_url("https://api.modrinth.com/v2/project/P7dR8mSH/version?" + query)
    version = next((entry for entry in versions if entry["version_type"] == "release"), None)
    if not version:
        raise RuntimeError("No official Fabric API release is published for " + minecraft)
    file = next(entry for entry in version["files"] if entry.get("primary"))
    target = vendor.fetch(file["url"], cache / file["filename"], file["hashes"]["sha1"])
    return target, {"id": "fabric-api", "version": version["version_number"], "projectId": "P7dR8mSH", "versionId": version["id"], "sha1": file["hashes"]["sha1"]}


def stage_mods(args: argparse.Namespace, game: Path, install: Path, primary: dict) -> tuple[list[dict], dict]:
    files = [args.artifact.resolve()]
    extras = {"profile": "baseline", "mods": []}
    if "fabric-api" in primary.get("depends", {}):
        file, _ = api_mod(args.minecraft, install / "mod-cache")
        files.append(file)
    if args.harness:
        harness = inspect_mod(args.harness, args.minecraft)
        if harness.get("id") == "renderscope" or not exact_mc(harness, args.minecraft):
            raise RuntimeError("Harness must be a separate mod declaring the exact target Minecraft")
        files.append(args.harness.resolve())
    if args.extra_mod_manifest:
        extras = vendor.read_json(args.extra_mod_manifest)
        if extras.get("loader") != "fabric" or extras.get("minecraftVersion") != args.minecraft:
            raise RuntimeError("Optional mod manifest targets a different loader or Minecraft version")
        qa_geometry_policy(args.minecraft, extras)
        for entry in extras["mods"]:
            file = args.extra_mod_manifest.parent / entry["file"]
            if Path(entry["file"]).name != entry["file"] or vendor.digest(file, "sha256") != entry["sha256"] or vendor.digest(file) != entry["sha1"]:
                raise RuntimeError("Optional Fabric mod checksum mismatch")
            if entry.get("sha512") and vendor.digest(file, "sha512") != entry["sha512"]:
                raise RuntimeError("Optional Fabric mod SHA-512 mismatch")
            metadata = inspect_mod(file, args.minecraft)
            if metadata["id"] != entry["id"] or metadata["version"] != entry["version"]:
                raise RuntimeError("Optional Fabric mod metadata mismatch")
            files.append(file.resolve())
        if "shaderPack" in extras:
            entry = extras["shaderPack"]
            file = args.extra_mod_manifest.parent / entry["file"]
            if Path(entry["file"]).name != entry["file"] or vendor.digest(file, "sha256") != entry["sha256"]:
                raise RuntimeError("Shader pack checksum mismatch")
            if entry.get("sha512") and vendor.digest(file, "sha512") != entry["sha512"]:
                raise RuntimeError("Shader pack SHA-512 mismatch")
            (game / "shaderpacks").mkdir(exist_ok=True)
            shutil.copy2(file, game / "shaderpacks" / entry["file"])
            (game / "config").mkdir(exist_ok=True)
            (game / "config/iris.properties").write_text("enableShaders=true\nshaderPack=" + entry["file"] + "\n", encoding="utf-8")
    mods = game / "mods"
    mods.mkdir(exist_ok=True)
    expected = {file.name for file in files}
    unexpected = [file.name for file in mods.glob("*.jar") if file.name not in expected]
    if unexpected:
        raise RuntimeError("Unexpected JARs in isolated Fabric profile: " + ", ".join(unexpected))
    proof = []
    for source in files:
        destination = mods / source.name
        if source.resolve() != destination.resolve():
            shutil.copy2(source, destination)
        metadata = inspect_mod(destination, args.minecraft)
        proof.append({"file": destination.name, "id": metadata["id"], "version": metadata["version"], "sha256": vendor.digest(destination, "sha256")})
    return proof, extras


def verify_result(args: argparse.Namespace, launched_ns: int, mods: list[dict]) -> dict:
    candidates = [args.output / name for name in ("smoke-result.json", "compat-result.json")]
    candidates = [path for path in candidates if path.is_file() and path.stat().st_mtime_ns >= launched_ns]
    if not candidates:
        raise RuntimeError("No fresh Fabric harness result; see installed-client-console.log")
    path = max(candidates, key=lambda value: value.stat().st_mtime_ns)
    result = vendor.read_json(path)
    started = datetime.fromisoformat(result["startedAt"].replace("Z", "+00:00")).timestamp()
    if started < launched_ns / 1e9 - 2 or result.get("success") is not True:
        raise RuntimeError("Fabric harness failed or wrote a stale result: " + str(result.get("error", result.get("phase", "unknown"))))
    if result.get("loader") != "Fabric" or result.get("loaderVersion") != args.loader or result.get("minecraftVersion") != args.minecraft:
        raise RuntimeError("Actual Minecraft/Fabric runtime does not match the requested versions")
    if result.get("environment") != "installed-production" or result.get("primaryJarSha256") != mods[0]["sha256"]:
        raise RuntimeError("Harness did not measure the staged production artifact")
    if args.extra_mod_manifest:
        profile = vendor.read_json(args.extra_mod_manifest)
        expected = {entry["id"]: entry["version"] for entry in profile["mods"]}
        if result.get("compatibilityProfile") != profile["profile"] or result.get("expectedOptionalMods") != expected:
            raise RuntimeError("Fabric harness did not validate the requested optional-mod profile")
        policy = qa_geometry_policy(args.minecraft, profile)
        limited_geometry = policy == MODERN_IRIS_POLICY
        if result.get("geometryPolicy", STRICT_GEOMETRY_POLICY) != policy:
            raise RuntimeError("Fabric harness used the wrong geometry QA policy")
        if limited_geometry:
            coverage = result.get("nativeGeometryCoverage", {})
            state = result.get("actualShaderState", {})
            if (coverage.get("status") != "unavailable-uninspected-native-abi"
                    or coverage.get("sodiumNativeVerticesAvailable") is not False
                    or coverage.get("irisNativeRenderTypeRequestsAvailable") is not False
                    or result.get("fullModeTimingWithPartialGeometry") is not True
                    or result.get("realEntityAndBlockEntitySamples") is not True
                    or result.get("timingOnlyFallback") is not True
                    or not state.get("shaderPackEnabled") or not state.get("shadowDetectionAvailable")
                    or result.get("actualSodiumNativeBatchesMeasured") is True
                    or result.get("actualShaderBufferRequestsMeasured") is True):
                raise RuntimeError("Pinned Iris profile lacks honest timing/shader/partial-geometry evidence")
        if not limited_geometry and "sodium" in expected and "iris" not in expected and result.get("actualSodiumNativeBatchesMeasured") is not True:
            raise RuntimeError("Real Sodium native geometry evidence is missing")
        if "sodium" in expected and result.get("nativeGeometryStoppedInTimingOnly") is not True:
            raise RuntimeError("Real Sodium native geometry/Timing Only evidence is missing")
        if "shaderPack" in profile and result.get("actualShaderShadowPassObserved") is not True:
            raise RuntimeError("Active Iris shadow-pass evidence is missing")
        if not limited_geometry and "shaderPack" in profile and result.get("actualShaderBufferRequestsMeasured") is not True:
            raise RuntimeError("Real Iris buffered RenderType request evidence is missing")
    for entry in mods:
        if vendor.digest(args.game_dir / "mods" / entry["file"], "sha256") != entry["sha256"]:
            raise RuntimeError("Staged mod changed during client run")
    return {"success": True, "minecraftVersion": args.minecraft, "loader": "Fabric", "loaderVersion": args.loader,
            "primaryJarSha256": mods[0]["sha256"], "resultFile": path.name, "resultSha256": vendor.digest(path, "sha256"),
            "geometryPolicy": qa_geometry_policy(args.minecraft, vendor.read_json(args.extra_mod_manifest)) if args.extra_mod_manifest else STRICT_GEOMETRY_POLICY,
            "verifiedAt": datetime.now(timezone.utc).isoformat()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--loader", required=True, help="Exact Fabric Loader version")
    parser.add_argument("--java")
    parser.add_argument("--artifact", required=True, type=Path, help="Production remapped RenderScope Fabric JAR")
    parser.add_argument("--harness", type=Path)
    parser.add_argument("--game-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--install-dir", type=Path)
    parser.add_argument("--extra-mod-manifest", type=Path)
    parser.add_argument("--launch", action="store_true")
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    if platform.system() != "Windows":
        parser.error("This launcher currently validates Windows native libraries only")
    if not re.fullmatch(r"[A-Za-z0-9_.+-]+", args.minecraft) or not re.fullmatch(r"[0-9.]+", args.loader):
        parser.error("Invalid Minecraft/Fabric version token")
    args.game_dir = args.game_dir.resolve(); args.output = args.output.resolve()
    if args.extra_mod_manifest:
        args.extra_mod_manifest = args.extra_mod_manifest.resolve()
    install = (args.install_dir or ROOT / ".tools" / ("installed-fabric-" + args.minecraft + "-" + args.loader)).resolve()
    install.mkdir(parents=True, exist_ok=True); args.game_dir.mkdir(parents=True, exist_ok=True)
    primary = inspect_mod(args.artifact, args.minecraft, True)
    vanilla, fabric, assets, classpath, natives, libraries = prepare_base(args.minecraft, args.loader, install)
    required_java = vanilla.get("javaVersion", {}).get("majorVersion", 17)
    declared_java = re.search(r"\d+", str(primary.get("depends", {}).get("java", "")))
    if declared_java:
        required_java = max(required_java, int(declared_java[0]))
    java, java_major = choose_java(args.java, required_java)
    mods, extras = stage_mods(args, args.game_dir, install, primary)
    username = "RenderScopeQA"
    offline_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + username).encode()).digest(), version=3).hex
    replacements = {"natives_directory": natives.as_posix(), "launcher_name": "RenderScopeInstalledQA", "launcher_version": primary["version"],
                    "classpath": os.pathsep.join(path.as_posix() for path in classpath), "library_directory": (install / "libraries").as_posix(),
                    "classpath_separator": os.pathsep, "version_name": fabric["id"], "version_type": "release", "auth_player_name": username,
                    "game_directory": args.game_dir.as_posix(), "assets_root": assets.as_posix(), "game_assets": assets.as_posix(),
                    "assets_index_name": vanilla["assetIndex"]["id"], "auth_uuid": offline_uuid, "auth_access_token": "0", "auth_session": "0",
                    "user_type": "legacy", "user_properties": "{}", "clientid": "00000000-0000-0000-0000-000000000000", "auth_xuid": "0",
                    "resolution_width": "1280", "resolution_height": "720"}
    jvm = vendor.expand_arguments(vanilla["arguments"]["jvm"] + fabric.get("arguments", {}).get("jvm", []), replacements)
    jvm += ["-Xms1G", "-Xmx5G", "-Drenderscope.expectedLoader=Fabric", "-Drenderscope.expectedLoaderVersion=" + args.loader,
            "-Drenderscope.expectedMinecraft=" + args.minecraft, "-Drenderscope.smoke.environment=installed-production",
            "-Drenderscope.compat.environment=installed-production", "-Drenderscope.smoke.output=" + args.output.as_posix(),
            "-Drenderscope.compat.output=" + args.output.as_posix(), "-Drenderscope.compat.profile=" + extras["profile"]]
    if extras["mods"]:
        expected = {entry["id"]: entry["version"] for entry in extras["mods"]}
        jvm.append("-Drenderscope.compat.expectedMods=" + json.dumps(expected, separators=(",", ":")))
    if "shaderPack" in extras:
        jvm.append("-Drenderscope.compat.requireShaders=true")
    jvm.append("-Drenderscope.compat.geometryPolicy=" + qa_geometry_policy(args.minecraft, extras))
    logging = vanilla.get("logging", {}).get("client")
    if logging:
        file = logging["file"]
        path = vendor.fetch(file["url"], install / "assets/log_configs" / file["id"], file["sha1"])
        jvm.append(logging["argument"].replace("${path}", path.as_uri()))
    game_arguments = vendor.expand_arguments(vanilla["arguments"]["game"] + fabric.get("arguments", {}).get("game", []), replacements)
    argfile = install / "launch.args"
    argfile.write_text("\n".join(vendor.java_argument(value) for value in jvm + [fabric["mainClass"]] + game_arguments) + "\n", encoding="utf-8")
    record = {"minecraftVersion": args.minecraft, "loader": "Fabric", "loaderVersion": args.loader, "mainClass": fabric["mainClass"],
              "javaRuntime": java_major, "developmentClasspath": False, "offlineTestIdentity": True,
              "vanillaClientSha1": vanilla["downloads"]["client"]["sha1"], "fabricProfileSha256": vendor.digest(install / "fabric-profile.json", "sha256"),
              "libraries": libraries, "mods": mods, "compatibilityProfile": extras["profile"],
              "geometryPolicy": qa_geometry_policy(args.minecraft, extras), "clientLaunched": False}
    vendor.write_json(install / "prepared-client.json", record)
    print("Official Fabric production profile prepared and verified. No Minecraft client was launched.", flush=True)
    if args.launch:
        args.output.mkdir(parents=True, exist_ok=True)
        launched_ns = time.time_ns(); record["clientLaunched"] = True
        record["launchedAt"] = datetime.now(timezone.utc).isoformat()
        vendor.write_json(args.output / "installed-launch.json", record)
        with (args.game_dir / "installed-client-console.log").open("w", encoding="utf-8") as log:
            result = subprocess.run([str(java), "@" + str(argfile)], cwd=args.game_dir, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        if result.returncode:
            raise RuntimeError("Installed Fabric client exited " + str(result.returncode) + "; see installed-client-console.log")
        if args.harness:
            verification = verify_result(args, launched_ns, mods)
            vendor.write_json(args.output / "installed-verification.json", verification)
            print("Fresh Fabric runtime, exact Minecraft/loader and measured production JAR hash verified.", flush=True)
        else:
            print("Manual client closed; no harness was supplied, so no automated runtime success is claimed.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Installed Fabric preparation/test failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
