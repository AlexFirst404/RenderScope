"""Prepare an isolated, official Forge production client; launch only with --launch.

Uses vendor version manifests and installer processors, never ForgeGradle's
development launcher or mapped Minecraft jars. All installation data is ignored.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROPERTIES = dict(re.findall(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\S+)\s*$", (ROOT / "gradle.properties").read_text(), re.MULTILINE))
MOD_VERSION = PROPERTIES["mod_version"]
MC = "1.20.1"
FORGE = "47.4.10"
PROFILE_ID = f"{MC}-forge-{FORGE}"
INSTALLER_NAME = f"forge-{MC}-{FORGE}-installer.jar"
INSTALLER_SHA1 = "66bfea9963bfa60d88bab6b2750e74a958392715"
INSTALLER_URL = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{MC}-{FORGE}/{INSTALLER_NAME}"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
USER_AGENT = "RenderScope local installed-client compatibility check/" + MOD_VERSION
MODS = (
    ("create-1.20.1-6.0.8.jar", "8amzvn9x", "LNytGWDc", "b13d912b9247a38d66d11598c121442585a1c1e9"),
    ("Mekanism-1.20.1-10.4.16.80.jar", "uxe1WQp4", "Ce6I4WUE", "b7f12ae710888b01746507a0aa8cb375f5897158"),
)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def digest(path: Path, algorithm: str = "sha1") -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def valid(path: Path, expected: str) -> bool:
    return path.is_file() and digest(path) == expected.lower()


def request_bytes(url: str) -> bytes:
    if not url.startswith("https://"):
        raise RuntimeError(f"HTTPS is required for vendor download: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=90) as response:
        return response.read()


def fetch(url: str, path: Path, expected: str, cache: list[Path] = ()) -> Path:
    if valid(path, expected):
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    for candidate in cache:
        if candidate != path and valid(candidate, expected):
            shutil.copy2(candidate, path)
            return path
    temporary = path.with_name(path.name + ".download")
    for attempt in range(3):
        try:
            data = request_bytes(url)
            if hashlib.sha1(data).hexdigest() != expected.lower():
                raise RuntimeError(f"Vendor SHA-1 mismatch: {path.name}")
            temporary.write_bytes(data)
            temporary.replace(path)
            return path
        except Exception:
            if attempt == 2:
                raise
    raise AssertionError("unreachable")


def find_java(explicit: str | None) -> Path:
    candidates = [Path(explicit)] if explicit else []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin/java.exe")
    candidates.extend(path / "bin/java.exe" for path in (ROOT / ".tools").glob("jdk-21*"))
    candidates.append(Path("C:/Program Files/Java/jdk-21/bin/java.exe"))
    for executable in candidates:
        if executable.is_dir():
            executable = executable / "bin/java.exe"
        if executable.is_file():
            version = subprocess.run([str(executable), "-version"], capture_output=True, text=True)
            if re.search(r'version "21[.\"]', version.stdout + version.stderr):
                return executable.resolve()
    raise RuntimeError("Install JDK 21 and set JAVA_HOME, or pass --java <jdk-21/bin/java.exe>.")


def maven_path(coordinate: str) -> str:
    coordinate = coordinate.strip("[]")
    name, _, extension = coordinate.partition("@")
    parts = name.split(":")
    group, artifact, version = parts[:3]
    classifier = "-" + parts[3] if len(parts) > 3 else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{classifier}.{extension or 'jar'}"


def library_caches(relative: str, coordinate: str) -> list[Path]:
    home = Path.home()
    gradle = Path(os.environ.get("GRADLE_USER_HOME", home / ".gradle")) / "caches"
    candidates = [gradle / "forge_gradle/libraries" / relative,
                  gradle / "forge_gradle/maven_downloader" / relative,
                  gradle / "forge_gradle/mcp_repo" / relative]
    appdata = os.environ.get("APPDATA")
    if appdata:
        candidates.append(Path(appdata) / ".minecraft/libraries" / relative)
    fields = coordinate.split(":")
    if len(fields) >= 3:
        folder = gradle / "modules-2/files-2.1" / fields[0] / fields[1] / fields[2].split("@")[0]
        if folder.is_dir():
            candidates.extend(folder.glob("*/" + Path(relative).name))
    return candidates


def allowed(rules: list[dict] | None) -> bool:
    if not rules:
        return True
    result = False
    arch = "aarch64" if platform.machine().lower() in ("arm64", "aarch64") else "amd64" if sys.maxsize > 2**32 else "x86"
    for rule in rules:
        condition = rule.get("os", {})
        if condition.get("name", "windows") != "windows":
            continue
        if "arch" in condition and not re.search(condition["arch"], arch):
            continue
        if "version" in condition and not re.search(condition["version"], platform.version()):
            continue
        features = {"has_custom_resolution": True}
        if any(features.get(key, False) != expected for key, expected in rule.get("features", {}).items()):
            continue
        result = rule["action"] == "allow"
    return result


def expand_arguments(arguments: list, replacements: dict[str, str]) -> list[str]:
    expanded = []
    for entry in arguments:
        if isinstance(entry, dict):
            if not allowed(entry.get("rules")):
                continue
            entries = entry["value"] if isinstance(entry["value"], list) else [entry["value"]]
        else:
            entries = [entry]
        for value in entries:
            for key, replacement in replacements.items():
                value = value.replace("${" + key + "}", replacement)
            if "${" in value:
                raise RuntimeError(f"Unresolved official launcher argument: {value}")
            expanded.append(value)
    return expanded


def download_libraries(documents: list[dict], install: Path) -> None:
    wanted = {}
    for document in documents:
        for library in document.get("libraries", []):
            if not allowed(library.get("rules")):
                continue
            artifact = library.get("downloads", {}).get("artifact")
            if artifact:
                previous = wanted.get(artifact["path"])
                if previous and previous[0]["sha1"] != artifact["sha1"]:
                    raise RuntimeError("Conflicting vendor library hashes: " + artifact["path"])
                wanted[artifact["path"]] = (artifact, library["name"])
    print(f"Preparing {len(wanted)} SHA-1-verified vendor libraries (using local caches where available).", flush=True)
    def one(item: tuple[dict, str]) -> None:
        artifact, coordinate = item
        relative = artifact["path"]
        fetch(artifact["url"], install / "libraries" / relative, artifact["sha1"], library_caches(relative, coordinate))
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(one, wanted.values()))


def prepare_assets(vanilla: dict, install: Path) -> Path:
    descriptor = vanilla["assetIndex"]
    index_path = fetch(descriptor["url"], install / "assets/indexes" / (descriptor["id"] + ".json"), descriptor["sha1"])
    objects = read_json(index_path)["objects"]
    gradle = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/forge_gradle/assets"
    roots = [gradle]
    if os.environ.get("APPDATA"):
        roots.append(Path(os.environ["APPDATA"]) / ".minecraft/assets")
    for cached in roots:
        if not valid(cached / "indexes" / index_path.name, descriptor["sha1"]):
            continue
        if all(valid(cached / "objects" / value["hash"][:2] / value["hash"], value["hash"]) for value in objects.values()):
            print(f"Reusing complete verified {len(objects)}-object asset cache.", flush=True)
            return cached.resolve()
    print(f"Preparing {len(objects)} verified Minecraft assets.", flush=True)
    def one(value: dict) -> None:
        sha = value["hash"]
        relative = Path("objects") / sha[:2] / sha
        fetch(f"https://resources.download.minecraft.net/{sha[:2]}/{sha}", install / "assets" / relative,
              sha, [cached / relative for cached in roots])
    with ThreadPoolExecutor(max_workers=12) as pool:
        list(pool.map(one, objects.values()))
    return (install / "assets").resolve()


def prepare_installation(install: Path, java: Path) -> tuple[dict, dict, Path]:
    install.mkdir(parents=True, exist_ok=True)
    installer = fetch(INSTALLER_URL, install / INSTALLER_NAME, INSTALLER_SHA1)
    with zipfile.ZipFile(installer) as archive:
        forge = json.loads(archive.read("version.json"))
        profile = json.loads(archive.read("install_profile.json"))
    manifest = json.loads(request_bytes(MANIFEST_URL))
    selected = next(version for version in manifest["versions"] if version["id"] == MC)
    vanilla_path = fetch(selected["url"], install / "versions" / MC / f"{MC}.json", selected["sha1"])
    vanilla = read_json(vanilla_path)
    client = vanilla["downloads"]["client"]
    gradle_repo = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/forge_gradle/minecraft_repo/versions" / MC
    appdata = Path(os.environ.get("APPDATA", install)) / ".minecraft/versions" / MC
    fetch(client["url"], vanilla_path.with_suffix(".jar"), client["sha1"], [gradle_repo / "client.jar", appdata / f"{MC}.jar"])
    mapping = vanilla["downloads"]["client_mappings"]
    mapping_path = install / "libraries" / maven_path(profile["data"]["MOJMAPS"]["client"])
    fetch(mapping["url"], mapping_path, mapping["sha1"], [gradle_repo / "client_mappings.txt"])
    download_libraries([vanilla, forge, profile], install)
    launcher_profile = install / "launcher_profiles.json"
    if not launcher_profile.exists():
        write_json(launcher_profile, {"profiles": {}})
    installed_json = install / "versions" / PROFILE_ID / f"{PROFILE_ID}.json"
    installed_client = install / "libraries" / maven_path(profile["data"]["PATCHED"]["client"])
    patched_sha = profile["data"]["PATCHED_SHA"]["client"].strip("'")
    if not installed_json.is_file() or not valid(installed_client, patched_sha):
        print("Running the official Forge client installer (no Minecraft client is launched).", flush=True)
        with (install / "installer-console.log").open("w", encoding="utf-8") as log:
            completed = subprocess.run([str(java), "-jar", str(installer), "--installClient", str(install)],
                                       cwd=install, stdout=log, stderr=subprocess.STDOUT)
        if completed.returncode:
            raise RuntimeError(f"Forge installer exited {completed.returncode}; see {install / 'installer-console.log'}")
    if not installed_json.is_file() or not valid(installed_client, patched_sha):
        raise RuntimeError("Official Forge installer did not produce the expected SHA-1-verified client.")
    forge = read_json(installed_json)
    if forge["mainClass"] != "cpw.mods.bootstraplauncher.BootstrapLauncher":
        raise RuntimeError("Unexpected installed production main class")
    assets = prepare_assets(vanilla, install)
    return vanilla, forge, assets


def selected_libraries(vanilla: dict, forge: dict) -> list[dict]:
    selected = {}
    for document in (vanilla, forge):
        for library in document["libraries"]:
            if allowed(library.get("rules")):
                coordinate = library["name"].split(":")
                key = tuple(coordinate[:2] + coordinate[3:])
                selected[key] = library
    return list(selected.values())


def prepare_native_libraries(libraries: list[dict], install: Path) -> Path:
    natives = install / "natives"
    natives.mkdir(exist_ok=True)
    suffix = "natives-windows-arm64" if platform.machine().lower() in ("arm64", "aarch64") else "natives-windows" if sys.maxsize > 2**32 else "natives-windows-x86"
    for library in libraries:
        if library["name"].split(":")[-1] != suffix:
            continue
        with zipfile.ZipFile(install / "libraries" / library["downloads"]["artifact"]["path"]) as archive:
            for member in archive.infolist():
                if member.filename.lower().endswith(".dll"):
                    (natives / Path(member.filename).name).write_bytes(archive.read(member))
    return natives


def stage_mods(game: Path, renderscope: Path, harness: Path | None) -> list[dict]:
    mods = game / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    if not renderscope.is_file():
        raise RuntimeError("Build the production Forge RenderScope JAR first, or pass --renderscope.")
    with zipfile.ZipFile(renderscope) as archive:
        if not json.loads(archive.read("renderscope.refmap.json")).get("mappings"):
            raise RuntimeError("RenderScope must have the production remapping table")
        if any("smoke" in name.lower() or "compat/CompatTest" in name for name in archive.namelist()):
            raise RuntimeError("Test harness code must not be included inside the production mod")
    files = [renderscope]
    source = ROOT / ".tools/compat-mods"
    source.mkdir(parents=True, exist_ok=True)
    for filename, version_id, project_id, sha in MODS:
        target = source / filename
        if not valid(target, sha):
            data = json.loads(request_bytes(f"https://api.modrinth.com/v2/version/{version_id}"))
            if data["project_id"] != project_id:
                raise RuntimeError("Unexpected vendor project for " + filename)
            descriptor = next(file for file in data["files"] if file["filename"] == filename)
            if descriptor["hashes"]["sha1"] != sha:
                raise RuntimeError("Pinned mod hash changed for " + filename)
            fetch(descriptor["url"], target, sha)
        files.append(target)
    if harness:
        if not harness.is_file():
            raise RuntimeError("Missing production/reobfuscated compatibility harness: " + str(harness))
        with zipfile.ZipFile(harness) as archive:
            metadata = archive.read("META-INF/mods.toml").decode()
            if 'modId="renderscope_compat"' not in metadata:
                raise RuntimeError("Expected the separate renderscope_compat test harness")
        files.append(harness)
    expected = {file.name for file in files}
    extra = [file.name for file in mods.glob("*.jar") if file.name not in expected]
    if extra:
        raise RuntimeError("Extra JARs in isolated profile; move previous RenderScope primary/harness JARs to .tools/installed-forge-compat/previous-mods manually, and resolve unknown mods explicitly: " + ", ".join(extra))
    installed = []
    for file in files:
        destination = mods / file.name
        if file.resolve() != destination.resolve():
            shutil.copy2(file, destination)
        installed.append({"file": file.name, "sha256": digest(destination, "sha256")})
    return installed


def java_argument(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def check_client_result(output: Path, game: Path, launched_ns: int, primary: dict) -> dict:
    """A normal Minecraft shutdown also has exit code zero after harness failure."""
    path = output / "compat-result.json"
    if not path.is_file() or path.stat().st_mtime_ns < launched_ns:
        raise RuntimeError("Installed client did not write a fresh compatibility result; see installed-client-console.log.")
    result = read_json(path)
    started = datetime.fromisoformat(result["startedAt"].replace("Z", "+00:00"))
    if started.timestamp() < launched_ns / 1_000_000_000 - 2:
        raise RuntimeError("Compatibility result belongs to an earlier client run.")
    if result.get("success") is not True:
        raise RuntimeError("Installed client compatibility checks failed: " + str(result.get("error", result.get("phase", "unknown"))))
    if result.get("loader") != "Forge" or result.get("loaderVersion") != FORGE:
        raise RuntimeError("Compatibility result did not measure the expected Forge runtime.")
    if result.get("environment") != "installed-production":
        raise RuntimeError("Compatibility result did not identify an installed production client.")
    if result.get("primaryJarSha256") != primary["sha256"]:
        raise RuntimeError("Measured RenderScope JAR does not match the staged production artifact.")
    if digest(game / "mods" / primary["file"], "sha256") != primary["sha256"]:
        raise RuntimeError("Staged production JAR changed while the client was running.")
    versions = {entry["id"]: entry["version"] for entry in result.get("testedMods", [])}
    # Mekanism's build 80 JAR declares 10.4.16 in mods.toml; its exact build is
    # pinned separately by the vendor SHA-1 and staged SHA-256 above.
    if versions.get("create") != "6.0.8" or versions.get("mekanism") != "10.4.16":
        raise RuntimeError("Compatibility result did not load the pinned Create and Mekanism versions.")
    return {"success": True, "runtimeLoader": result["loader"], "runtimeLoaderVersion": result["loaderVersion"],
            "primaryJarSha256": primary["sha256"], "compatibilityResultSha256": digest(path, "sha256"),
            "verifiedAt": datetime.now(timezone.utc).isoformat()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launch", action="store_true", help="Explicitly launch after preparing; default only prepares")
    parser.add_argument("--java", help="JDK 21 java executable or JDK directory")
    parser.add_argument("--harness", type=Path, help="Separate reobfuscated renderscope_compat JAR")
    parser.add_argument("--renderscope", type=Path, default=ROOT / "build/forge/libs" / f"renderscope-forge-{MOD_VERSION}+mc{MC}.jar")
    parser.add_argument("--install-dir", type=Path, default=ROOT / ".tools/installed-forge-compat")
    parser.add_argument("--game-dir", type=Path, default=ROOT / "run-compat-installed")
    parser.add_argument("--output", type=Path, default=ROOT / "distribution/forge/compatibility-installed")
    args = parser.parse_args()
    if platform.system() != "Windows":
        parser.error("This installed-client QA launcher currently targets Windows.")
    if args.launch and not args.harness:
        parser.error("--launch requires --harness pointing to the separate reobfuscated compatibility JAR.")
    install, game, output = args.install_dir.resolve(), args.game_dir.resolve(), args.output.resolve()
    java = find_java(args.java)
    game.mkdir(parents=True, exist_ok=True)
    vanilla, forge, assets = prepare_installation(install, java)
    libraries = selected_libraries(vanilla, forge)
    natives = prepare_native_libraries(libraries, install)
    mods = stage_mods(game, args.renderscope.resolve(), args.harness.resolve() if args.harness else None)
    classpath = [install / "libraries" / library["downloads"]["artifact"]["path"] for library in libraries]
    vanilla_client = install / "versions" / MC / f"{MC}.jar"
    classpath.append(vanilla_client)
    missing = [str(file) for file in classpath if not file.is_file()]
    if missing:
        raise RuntimeError("Missing installed launcher classpath files: " + ", ".join(missing))
    username = "RenderScopeQA"
    offline_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + username).encode()).digest(), version=3).hex
    replacements = {"natives_directory": natives.as_posix(), "launcher_name": "RenderScopeInstalledQA",
                    "launcher_version": MOD_VERSION, "classpath": os.pathsep.join(file.as_posix() for file in classpath),
                    "library_directory": (install / "libraries").as_posix(), "classpath_separator": os.pathsep,
                    "version_name": PROFILE_ID, "version_type": "release", "auth_player_name": username,
                    "game_directory": game.as_posix(), "assets_root": assets.as_posix(),
                    "assets_index_name": vanilla["assetIndex"]["id"], "auth_uuid": offline_uuid,
                    "auth_access_token": "0", "auth_session": "0", "user_type": "legacy", "user_properties": "{}",
                    "clientid": "00000000-0000-0000-0000-000000000000", "auth_xuid": "0",
                    "resolution_width": "1280", "resolution_height": "720"}
    jvm = expand_arguments(vanilla["arguments"]["jvm"] + forge["arguments"]["jvm"], replacements)
    # The inherited vanilla JAR is named 1.20.1.jar, while Forge's generated
    # ignoreList expands version_name to the Forge profile ID. Exclude the
    # actual inherited filename from BootstrapLauncher's module layer too.
    # FML's production client handler loads its installed SRG/extra/patched
    # Minecraft libraries separately; loading vanilla as another module would
    # export the same Minecraft packages twice.
    ignore_index = next((index for index, value in enumerate(jvm) if value.startswith("-DignoreList=")), None)
    if ignore_index is None:
        raise RuntimeError("Installed Forge profile is missing its BootstrapLauncher ignoreList.")
    if vanilla_client.name not in jvm[ignore_index].partition("=")[2].split(","):
        jvm[ignore_index] += "," + vanilla_client.name
    logging = vanilla.get("logging", {}).get("client")
    if logging:
        file = logging["file"]
        config = fetch(file["url"], install / "assets/log_configs" / file["id"], file["sha1"])
        # A drive-letter path is interpreted as URL scheme D by Log4j2.
        jvm.append(logging["argument"].replace("${path}", config.as_uri()))
    jvm = ["-Xms1G", "-Xmx5G", "-Drenderscope.expectedLoader=Forge", f"-Drenderscope.expectedLoaderVersion={FORGE}",
           "-Drenderscope.compat.environment=installed-production", "-Drenderscope.compat.output=" + output.as_posix()] + jvm
    game_arguments = expand_arguments(vanilla["arguments"]["game"] + forge["arguments"]["game"], replacements)
    argfile = install / "launch.args"
    argfile.write_text("\n".join(java_argument(value) for value in jvm + [forge["mainClass"]] + game_arguments) + "\n", encoding="utf-8")
    record = {"minecraftVersion": MC, "loader": "Forge", "loaderVersion": FORGE,
              "mainClass": forge["mainClass"], "launchTarget": "forgeclient", "developmentClasspath": False,
              "vanillaClientBootstrapIgnored": True,
              "forgeInstallerSha1": INSTALLER_SHA1, "vanillaClientSha1": vanilla["downloads"]["client"]["sha1"],
              "javaRuntime": 21, "offlineTestIdentity": True, "mods": mods,
              "clientLaunched": False}
    write_json(install / "prepared-client.json", record)
    print("Installed production profile prepared. No Minecraft client or Gradle was launched.", flush=True)
    print("Launch after the final rebuild using this script with --harness <reobfuscated-compat.jar> --launch.", flush=True)
    if args.launch:
        output.mkdir(parents=True, exist_ok=True)
        record["clientLaunched"] = True
        launched_ns = time.time_ns()
        record["launchedAt"] = datetime.now(timezone.utc).isoformat()
        write_json(output / "installed-launch.json", record)
        print("Launching the installed Forge production client with the staged JARs.", flush=True)
        with (game / "installed-client-console.log").open("w", encoding="utf-8") as log:
            result = subprocess.run([str(java), "@" + str(argfile)], cwd=game, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Installed client exited {result.returncode}; see {game / 'installed-client-console.log'}")
        verification = check_client_result(output, game, launched_ns, mods[0])
        write_json(output / "installed-verification.json", verification)
        print("Installed production compatibility checks passed; runtime loader, fresh result, and measured JAR hash verified.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Installed client preparation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
