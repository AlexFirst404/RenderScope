"""Prepare and optionally run an exact official Forge/NeoForge production client.

The upstream installer owns Minecraft transformation. Vendor checksums, declared
Minecraft constraints, isolated test mods and fresh measured-JAR evidence remain
mandatory; this never uses a Gradle development classpath or account credentials.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import tomllib
import uuid
import zipfile
import urllib.error

ROOT = Path(__file__).resolve().parents[1]


def module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


vendor = module("native_vendor", "test-installed-client.py")
fabric = module("native_java", "test-fabric-client.py")


def json_url(url: str) -> dict:
    return json.loads(request(url))


def request(url: str) -> bytes:
    for attempt in range(3):
        try:
            return vendor.request_bytes(url)
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
    raise AssertionError("unreachable")


def inspect_mod(path: Path, mc: str, loader: str, primary: bool = False) -> dict:
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        descriptor = "META-INF/neoforge.mods.toml" if "META-INF/neoforge.mods.toml" in names else "META-INF/mods.toml"
        metadata = tomllib.loads(jar.read(descriptor).decode("utf-8"))
        if len(metadata.get("mods", [])) != 1:
            raise RuntimeError("Expected one mod in isolated test JAR: " + path.name)
        mod = metadata["mods"][0]
        dependencies = metadata.get("dependencies", {}).get(mod["modId"], [])
        if loader == "neoforge" and mc in ("1.20.2", "1.20.3"):
            if any(type(item.get("mandatory")) is not bool for item in dependencies):
                raise RuntimeError("NeoForge FML 1 requires boolean mandatory in every dependency: " + path.name)
        minecraft = next((item for item in dependencies if item["modId"] == "minecraft"), None)
        accepted = {"[" + mc + "]"}
        if mc == "1.20.1":
            accepted.add("[1.20.1,1.20.2)")
        if not minecraft or minecraft["versionRange"] not in accepted or minecraft.get("side") != "CLIENT":
            raise RuntimeError("Test JAR must declare the exact Minecraft client target: " + path.name)
        if primary:
            if mod["modId"] != "renderscope" or any("NativeSmoke" in name or "/smoke/" in name or "/compat/CompatTest" in name for name in names):
                raise RuntimeError("Primary artifact is not an isolated production RenderScope JAR")
            loader_dependency = "forge" if loader == "forge" or mc == "1.20.1" else "neoforge"
            if not any(item["modId"] == loader_dependency for item in dependencies):
                raise RuntimeError("Production artifact targets a different native loader")
            for name in names:
                if name.endswith("mixins.json") and "/" not in name:
                    refmap = json.loads(jar.read(name)).get("refmap")
                    if refmap and refmap not in names:
                        raise RuntimeError("Declared production refmap is absent: " + refmap)
        return {"id": mod["modId"], "version": mod["version"],
                "nestedRuntime": any(name.startswith("META-INF/jarjar/") and name.endswith(".jar") for name in names)}


def installer_url(args) -> tuple[str, str]:
    if args.loader == "forge":
        coordinate = f"net.minecraftforge:forge:{args.minecraft}-{args.loader_version}:installer"
        base = "https://maven.minecraftforge.net/"
    elif args.minecraft == "1.20.1":
        coordinate = f"net.neoforged:forge:{args.minecraft}-{args.loader_version}:installer"
        base = "https://maven.neoforged.net/releases/"
    else:
        coordinate = f"net.neoforged:neoforge:{args.loader_version}:installer"
        base = "https://maven.neoforged.net/releases/"
    return base + vendor.maven_path(coordinate), coordinate


def processor_outputs(profile: dict, install: Path, installer: Path, mc: str) -> list[tuple[Path, str]]:
    values = {"ROOT": str(install), "LIBRARY_DIR": str(install / "libraries"), "SIDE": "client",
              "MINECRAFT_JAR": str(install / "versions" / mc / (mc + ".jar")), "INSTALLER": str(installer)}
    for key, sides in profile.get("data", {}).items():
        if isinstance(sides, dict) and "client" in sides:
            values[key] = sides["client"]

    def expand(value: str) -> str:
        for _ in range(20):
            previous = value
            for key, replacement in values.items():
                value = value.replace("{" + key + "}", replacement)
            if value == previous:
                break
        if "{" in value:
            raise RuntimeError("Unresolved official installer output token: " + value)
        value = value.strip("'")
        if value.startswith("[") and value.endswith("]"):
            value = str(install / "libraries" / vendor.maven_path(value[1:-1]))
        return value

    outputs = []
    for processor in profile.get("processors", []):
        if "sides" in processor and "client" not in processor["sides"]:
            continue
        for location, checksum in processor.get("outputs", {}).items():
            expected = expand(checksum)
            if not re.fullmatch(r"[0-9a-fA-F]{40}", expected):
                raise RuntimeError("Official installer output lacks an exact SHA-1")
            target = Path(expand(location))
            if not target.is_absolute():
                target = install / target
            if not target.resolve().is_relative_to(install):
                raise RuntimeError("Official installer output escapes isolated install directory")
            outputs.append((target, expected.lower()))
    return outputs


def assets_for(vanilla: dict, install: Path) -> Path:
    descriptor = vanilla["assetIndex"]
    caches = list((ROOT / ".tools").glob("installed-*/assets"))
    for cached in caches:
        index = cached / "indexes" / (descriptor["id"] + ".json")
        if not vendor.valid(index, descriptor["sha1"]):
            continue
        objects = vendor.read_json(index)["objects"]
        if all(vendor.valid(cached / "objects" / item["hash"][:2] / item["hash"], item["hash"]) for item in objects.values()):
            print(f"Reusing {len(objects)} verified assets.", flush=True)
            return cached.resolve()
    return vendor.prepare_assets(vanilla, install)


def cache_mapping_output(profile: dict, vanilla: dict, install: Path, java: Path,
                         outputs: list[tuple[Path, str]]) -> bool:
    """Populate one exact generated cache entry; the official output SHA-1 is mandatory."""
    coordinate = profile.get("data", {}).get("MOJMAPS", {}).get("client", "").strip("[]")
    if not coordinate.endswith("@tsrg"):
        return False
    target = install / "libraries" / vendor.maven_path(coordinate)
    checksum = next((expected for path, expected in outputs if path == target), None)
    if not checksum or vendor.valid(target, checksum):
        return False
    processor = next((entry for entry in profile.get("processors", [])
                      if "DOWNLOAD_MOJMAPS" in entry.get("args", []) and "--sanitize" in entry.get("args", [])
                      and "{MOJMAPS}" in entry.get("args", [])), None)
    if not processor:
        return False
    parser = next((entry for entry in processor.get("classpath", []) if entry.startswith("net.minecraftforge:srgutils:")), None)
    if not parser:
        return False
    raw = vanilla["downloads"]["client_mappings"]
    source = vendor.fetch(raw["url"], install / "vendor-inputs" / (vanilla["id"] + "-mappings.txt"), raw["sha1"])
    arguments = processor["args"]
    format_name = arguments[arguments.index("--format") + 1] if "--format" in arguments else "TSRG"
    target.parent.mkdir(parents=True, exist_ok=True)
    converter = ROOT / "scripts/java/ConvertVendorMappings.java"
    with (install / "mapping-cache-console.log").open("w", encoding="utf-8") as log:
        converted = subprocess.run([str(java), "--class-path", str(install / "libraries" / vendor.maven_path(parser)),
                                    str(converter), str(source), str(target), format_name],
                                   stdout=log, stderr=subprocess.STDOUT, timeout=60)
    if converted.returncode or not vendor.valid(target, checksum):
        raise RuntimeError("Upstream mapping conversion did not match the official pinned SHA-1")
    print("Cached official sanitized mappings with exact installer output SHA-1.", flush=True)
    return True


def prepare(args, install: Path) -> tuple[dict, dict, Path, list[dict], Path, dict, Path, int]:
    url, coordinate = installer_url(args)
    checksum = request(url + ".sha1").decode("ascii").strip().split()[0]
    if not re.fullmatch(r"[0-9a-fA-F]{40}", checksum):
        raise RuntimeError("Official installer SHA-1 is malformed")
    installer = vendor.fetch(url, install / Path(vendor.maven_path(coordinate)).name, checksum)
    with zipfile.ZipFile(installer) as jar:
        loader_profile = json.loads(jar.read("version.json"))
        profile = json.loads(jar.read("install_profile.json"))
    if profile.get("minecraft") != args.minecraft or loader_profile.get("inheritsFrom") != args.minecraft:
        raise RuntimeError("Official installer does not target the requested Minecraft version")
    manifest = json_url(vendor.MANIFEST_URL)
    release = next((item for item in manifest["versions"] if item["id"] == args.minecraft), None)
    if not release or release["type"] != "release":
        raise RuntimeError("Expected an official stable Minecraft release")
    vanilla_file = vendor.fetch(release["url"], install / "versions" / args.minecraft / (args.minecraft + ".json"), release["sha1"])
    vanilla = vendor.read_json(vanilla_file)
    if vanilla["id"] != args.minecraft:
        raise RuntimeError("Mojang manifest identity mismatch")
    required_java = max(21, vanilla.get("javaVersion", {}).get("majorVersion", 17))
    java, java_major = fabric.choose_java(args.java, required_java)
    client = vanilla["downloads"]["client"]
    caches = list((ROOT / ".tools").glob(f"installed-*/versions/{args.minecraft}/{args.minecraft}.jar"))
    caches.extend(vendor.library_caches(f"versions/{args.minecraft}/client.jar", "com.mojang:minecraft:" + args.minecraft))
    vendor.fetch(client["url"], vanilla_file.with_suffix(".jar"), client["sha1"], caches)
    mapping = profile.get("data", {}).get("MOJMAPS", {}).get("client")
    if mapping and mapping.strip("[]").endswith("@txt"):
        # Later Forge installers use this coordinate for a generated, sanitized
        # TSRG output. Raw Mojang ProGuard text must never overwrite that file.
        mapping = mapping.strip("[]")
        descriptor = vanilla["downloads"]["client_mappings"]
        vendor.fetch(descriptor["url"], install / "libraries" / vendor.maven_path(mapping), descriptor["sha1"])
    # Empty URLs denote installer-generated outputs, not downloadable artifacts.
    downloadable = []
    for doc in (vanilla, loader_profile, profile):
        downloadable.append({"libraries": [item for item in doc.get("libraries", [])
                                           if item.get("downloads", {}).get("artifact", {}).get("url")]})
    vendor.download_libraries(downloadable, install)
    launcher_file = install / "launcher_profiles.json"
    if not launcher_file.is_file():
        vendor.write_json(launcher_file, {"profiles": {}})
    outputs = processor_outputs(profile, install, installer, args.minecraft)
    mapping_cache_verified = cache_mapping_output(profile, vanilla, install, java, outputs)
    installed_file = install / "versions" / loader_profile["id"] / (loader_profile["id"] + ".json")
    marker = install / "installer-verified.json"
    marker_ok = marker.is_file() and vendor.read_json(marker).get("installerSha1") == checksum
    if not installed_file.is_file() or not marker_ok or any(not vendor.valid(path, expected) for path, expected in outputs):
        print("Running the official native client installer; no game is launched.", flush=True)
        installer_log = install / "installer-console.log"
        for attempt in range(3):
            with installer_log.open("w", encoding="utf-8") as log:
                result = subprocess.run([str(java), "-jar", str(installer), "--installClient", str(install)],
                                        cwd=install, stdout=log, stderr=subprocess.STDOUT, timeout=600)
            if not result.returncode:
                break
            detail = installer_log.read_text(encoding="utf-8", errors="replace")
            if attempt == 2 or not any(token in detail for token in ("SSLHandshakeException", "SSLException", "Connection reset")):
                break
            shutil.copy2(installer_log, install / f"installer-network-attempt-{attempt + 1}.log")
            print("Retrying a transient verified-HTTPS installer connection.", flush=True)
        if result.returncode:
            raise RuntimeError("Official installer failed; see " + str(install / "installer-console.log"))
    if not installed_file.is_file() or any(not vendor.valid(path, expected) for path, expected in outputs):
        raise RuntimeError("Official installer output SHA-1 validation failed")
    installed = vendor.read_json(installed_file)
    if installed.get("mainClass") != loader_profile["mainClass"] or installed.get("inheritsFrom") != args.minecraft:
        raise RuntimeError("Installed loader profile differs from its verified official installer")
    vendor.write_json(marker, {"installerSha1": checksum, "verifiedOutputs": len(outputs)})
    libraries = vendor.selected_libraries(vanilla, installed)
    for item in libraries:
        artifact = item.get("downloads", {}).get("artifact")
        path = install / "libraries" / (artifact["path"] if artifact else vendor.maven_path(item["name"]))
        if not path.is_file() or (artifact and artifact.get("sha1") and not vendor.valid(path, artifact["sha1"])):
            raise RuntimeError("Missing or modified installed runtime library: " + item["name"])
    natives = vendor.prepare_native_libraries(libraries, install)
    assets = assets_for(vanilla, install)
    proof = {"installerSha1": checksum, "installerCoordinate": coordinate,
             "vanillaClientSha1": client["sha1"], "verifiedProcessorOutputs": len(outputs),
             "verifiedMappingCache": mapping_cache_verified,
             "officialNestedMixinExtras": nested_runtime(libraries + profile.get("libraries", []), install)}
    return vanilla, installed, assets, libraries, natives, proof, java, java_major


def stage(args) -> list[dict]:
    files = [args.artifact]
    if args.harness:
        files.append(args.harness)
    target = args.game_dir / "mods"
    target.mkdir(exist_ok=True)
    expected = {path.name for path in files}
    if any(path.name not in expected for path in target.glob("*.jar")):
        raise RuntimeError("Unexpected mod in isolated native profile; use another game directory")
    result = []
    for index, path in enumerate(files):
        metadata = inspect_mod(path, args.minecraft, args.loader, index == 0)
        if index and metadata["id"] == "renderscope":
            raise RuntimeError("Harness must be a separate test-only mod")
        destination = target / path.name
        if path.resolve() != destination.resolve():
            shutil.copy2(path, destination)
        result.append(dict(file=destination.name, sha256=vendor.digest(destination, "sha256"), **metadata))
    return result


def nested_runtime(libraries: list[dict], install: Path) -> list[dict]:
    """NeoForge can provide MixinExtras inside its checksum-verified universal JAR."""
    found = []
    for entry in libraries:
        if not entry["name"].startswith(("net.neoforged:neoforge:", "net.minecraftforge:forge:", "net.neoforged:forge:")):
            continue
        artifact = entry.get("downloads", {}).get("artifact")
        if not artifact or not artifact.get("sha1"):
            continue
        path = install / "libraries" / artifact["path"]
        if not vendor.valid(path, artifact["sha1"]):
            raise RuntimeError("Official native container checksum changed")
        with zipfile.ZipFile(path) as outer:
            if "META-INF/jarjar/metadata.json" not in outer.namelist():
                continue
            metadata = json.loads(outer.read("META-INF/jarjar/metadata.json"))
            for nested in metadata.get("jars", []):
                identifier = nested.get("identifier", {})
                if identifier.get("group") != "io.github.llamalad7" or not identifier.get("artifact", "").startswith("mixinextras-"):
                    continue
                version = nested.get("version", {}).get("artifactVersion", "")
                parts = re.match(r"(\d+)\.(\d+)\.(\d+)", version)
                if not parts or tuple(map(int, parts.groups())) < (0, 4, 1):
                    continue
                data = outer.read(nested["path"])
                with zipfile.ZipFile(io.BytesIO(data)) as inner:
                    if "com/llamalad7/mixinextras/injector/wrapmethod/WrapMethod.class" not in inner.namelist():
                        raise RuntimeError("Official embedded MixinExtras lacks the required API")
                found.append({"container": entry["name"], "path": nested["path"], "version": version,
                              "sha256": hashlib.sha256(data).hexdigest()})
    return found


def verify(args, launched_ns: int, mods: list[dict]) -> dict:
    result_file = args.output / "smoke-result.json"
    if not result_file.is_file() or result_file.stat().st_mtime_ns < launched_ns:
        raise RuntimeError("No fresh native smoke result was produced")
    result = vendor.read_json(result_file)
    started = datetime.fromisoformat(result["startedAt"].replace("Z", "+00:00")).timestamp()
    if started < launched_ns / 1e9 - 2 or result.get("success") is not True:
        raise RuntimeError("Native harness failed or is stale: " + str(result.get("error", result.get("phase"))))
    loader = "NeoForge" if args.loader == "neoforge" else "Forge"
    if result.get("loader") != loader or result.get("loaderVersion") != args.loader_version or result.get("minecraftVersion") != args.minecraft:
        raise RuntimeError("Actual Minecraft/native loader differs from the requested exact versions")
    if result.get("environment") != "installed-production" or result.get("primaryJarSha256") != mods[0]["sha256"]:
        raise RuntimeError("Native harness did not measure the staged production artifact")
    for mod in mods:
        if vendor.digest(args.game_dir / "mods" / mod["file"], "sha256") != mod["sha256"]:
            raise RuntimeError("Staged mod changed during the native run")
    return {"success": True, "minecraftVersion": args.minecraft, "loader": loader,
            "loaderVersion": args.loader_version, "primaryJarSha256": mods[0]["sha256"],
            "resultFile": result_file.name, "resultSha256": vendor.digest(result_file, "sha256"),
            "verifiedAt": datetime.now(timezone.utc).isoformat()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--loader", required=True, choices=["forge", "neoforge"])
    parser.add_argument("--loader-version", required=True)
    parser.add_argument("--java")
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--harness", type=Path)
    parser.add_argument("--game-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--install-dir", type=Path)
    parser.add_argument("--launch", action="store_true")
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    pause = ROOT / ".tools/native-world.pause"
    if args.launch and pause.is_file():
        raise RuntimeError("Native world queue paused before client launch: " + pause.read_text(encoding="utf-8").strip())
    if platform.system() != "Windows":
        parser.error("Only the verified Windows native rules are implemented")
    if not re.fullmatch(r"[0-9A-Za-z.+-]+", args.minecraft) or not re.fullmatch(r"[0-9A-Za-z.+-]+", args.loader_version):
        parser.error("Invalid upstream version token")
    args.artifact = args.artifact.resolve()
    args.harness = args.harness.resolve() if args.harness else None
    args.game_dir = args.game_dir.resolve(); args.output = args.output.resolve()
    args.game_dir.mkdir(parents=True, exist_ok=True)
    install = (args.install_dir or ROOT / ".tools" / f"installed-{args.loader}-{args.minecraft}-{args.loader_version}").resolve()
    install.mkdir(parents=True, exist_ok=True)
    primary = inspect_mod(args.artifact, args.minecraft, args.loader, True)
    vanilla, profile, assets, libraries, natives, proof, java, java_major = prepare(args, install)
    builtin_mixins = [entry["name"] for entry in libraries if entry["name"].startswith("io.github.llamalad7:mixinextras-")]
    nested_mixins = proof["officialNestedMixinExtras"]
    if not primary["nestedRuntime"] and not builtin_mixins and not nested_mixins:
        raise RuntimeError("No verified nested or upstream runtime MixinExtras dependency; do not use a slim JAR")
    proof["officialRuntimeMixinExtras"] = builtin_mixins
    proof["officialNestedMixinExtras"] = nested_mixins
    mods = stage(args)
    classpath = [install / "libraries" / item.get("downloads", {}).get("artifact", {}).get("path", vendor.maven_path(item["name"])) for item in libraries]
    client = install / "versions" / args.minecraft / (args.minecraft + ".jar")
    classpath.append(client)
    username = "RenderScopeQA"
    offline_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + username).encode()).digest(), version=3).hex
    replacements = {"natives_directory": natives.as_posix(), "launcher_name": "RenderScopeInstalledQA", "launcher_version": "1",
        "classpath": os.pathsep.join(path.as_posix() for path in classpath), "library_directory": (install / "libraries").as_posix(),
        "classpath_separator": os.pathsep, "version_name": profile["id"], "version_type": "release", "auth_player_name": username,
        "game_directory": args.game_dir.as_posix(), "assets_root": assets.as_posix(), "game_assets": assets.as_posix(),
        "assets_index_name": vanilla["assetIndex"]["id"], "auth_uuid": offline_uuid, "auth_access_token": "0", "auth_session": "0",
        "user_type": "legacy", "user_properties": "{}", "clientid": "00000000-0000-0000-0000-000000000000", "auth_xuid": "0",
        "resolution_width": "1280", "resolution_height": "720"}
    jvm = vendor.expand_arguments(vanilla["arguments"]["jvm"] + profile.get("arguments", {}).get("jvm", []), replacements)
    # Forge's newer bootstrap resolves the transformed game via LibraryFinder.
    # With a separate gameDir, its default relative "libraries" is not the
    # verified installer directory used by this isolated launcher.
    if not any(value.startswith("-DlibraryDirectory=") for value in jvm):
        jvm.append("-DlibraryDirectory=" + (install / "libraries").as_posix())
    # Production bootstrap owns the transformed Minecraft module. The inherited
    # vanilla filename must join its official ignoreList to prevent duplicate modules.
    for index, value in enumerate(jvm):
        if value.startswith("-DignoreList=") and client.name not in value.partition("=")[2].split(","):
            jvm[index] += "," + client.name
    loader = "NeoForge" if args.loader == "neoforge" else "Forge"
    jvm += ["-Xms1G", "-Xmx5G", "-Drenderscope.expectedLoader=" + loader,
        "-Drenderscope.expectedLoaderVersion=" + args.loader_version, "-Drenderscope.expectedMinecraft=" + args.minecraft,
        "-Drenderscope.smoke.environment=installed-production", "-Drenderscope.smoke.output=" + args.output.as_posix(),
        "-Drenderscope.modern.smoke.environment=installed-production", "-Drenderscope.modern.smoke.output=" + args.output.as_posix(),
        "-Drenderscope.modern.smoke.artifact=" + (args.game_dir / "mods" / mods[0]["file"]).as_posix()]
    logging = vanilla.get("logging", {}).get("client")
    if logging:
        file = logging["file"]
        config = vendor.fetch(file["url"], install / "assets/log_configs" / file["id"], file["sha1"])
        jvm.append(logging["argument"].replace("${path}", config.as_uri()))
    game = vendor.expand_arguments(vanilla["arguments"]["game"] + profile.get("arguments", {}).get("game", []), replacements)
    argfile = install / "launch.args"
    argfile.write_text("\n".join(vendor.java_argument(value) for value in jvm + [profile["mainClass"]] + game) + "\n", encoding="utf-8")
    record = dict(minecraftVersion=args.minecraft, loader=loader, loaderVersion=args.loader_version, javaRuntime=java_major,
        mainClass=profile["mainClass"], developmentClasspath=False, offlineTestIdentity=True, mods=mods, clientLaunched=False, **proof)
    vendor.write_json(install / "prepared-client.json", record)
    print("Official native profile prepared with verified installer, libraries and assets; no game launched.", flush=True)
    if args.launch:
        args.output.mkdir(parents=True, exist_ok=True)
        launched_ns = time.time_ns(); record["clientLaunched"] = True
        record["launchedAt"] = datetime.now(timezone.utc).isoformat()
        vendor.write_json(args.output / "installed-launch.json", record)
        with (args.game_dir / "installed-client-console.log").open("w", encoding="utf-8") as log:
            result = subprocess.run([str(java), "@" + str(argfile)], cwd=args.game_dir, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        if result.returncode:
            raise RuntimeError("Native production client exited " + str(result.returncode) + "; inspect installed-client-console.log")
        if args.harness:
            vendor.write_json(args.output / "installed-verification.json", verify(args, launched_ns, mods))
            print("Fresh native runtime, exact versions and measured production JAR hash verified.", flush=True)
        else:
            print("Manual client closed; no automated success is claimed without a harness.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Native installed preparation/test failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
