"""Build exact adapters from checked-in target manifests, then audit their JARs.

Examples: python scripts/build-all.py --minecraft 1.20.1 --loader fabric
          python scripts/build-all.py --all --java21 /jdk21 --java25 /jdk25
With no selection, print available build targets without launching anything.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFESTS = ["docs/compatibility/build-targets.json", "mc21/targets.json", "loader21/targets.json",
             "modern/targets.json", "loader-mapped/targets.json"]


def targets() -> list[dict]:
    result = []
    for relative in MANIFESTS:
        file = ROOT / relative
        if file.is_file():
            result.extend(json.loads(file.read_text(encoding="utf-8"))["targets"])
    identities = [(entry["minecraft"], entry["loader"]) for entry in result]
    if len(identities) != len(set(identities)):
        raise RuntimeError("Duplicate Minecraft/loader targets in build manifests")
    return result


def java_home(major: int, explicit: str | None) -> Path:
    candidates = [explicit, os.environ.get(f"JAVA_HOME_{major}_X64"), os.environ.get("JAVA_HOME")]
    candidates.extend(str(path) for path in (ROOT / ".tools").glob(f"jdk{major}/jdk*"))
    candidates.extend(str(path) for path in (ROOT / ".tools").glob(f"jdk-{major}*"))
    executable = "java.exe" if os.name == "nt" else "java"
    for candidate in candidates:
        if not candidate:
            continue
        home = Path(candidate).resolve()
        java = home / "bin" / executable
        if not java.is_file():
            continue
        check = subprocess.run([str(java), "-version"], text=True, capture_output=True)
        if re.search(r'version "' + str(major) + r'[.\"]', check.stdout + check.stderr):
            return home
    raise RuntimeError(f"JDK {major} is required. Pass --java{major} with its home directory.")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit(entry: dict) -> dict:
    artifact = ROOT / entry["artifact"]
    if not artifact.is_file():
        raise RuntimeError("Missing main artifact: " + entry["artifact"])
    with zipfile.ZipFile(artifact) as jar:
        names = jar.namelist()
        own = [name for name in names if name.startswith("dev/renderscope/") and name.endswith(".class")]
        if not own or any("/smoke/" in name or "/compat/CompatTest" in name or "NativeSmoke" in name for name in own):
            raise RuntimeError("Main JAR is empty or contains isolated QA classes")
        for notice in ["LICENSE", "THIRD_PARTY_NOTICES.md"]:
            if jar.read("META-INF/" + notice) != (ROOT / notice).read_bytes():
                raise RuntimeError("Main JAR has missing or outdated legal notice: " + notice)
        max_class = max(int.from_bytes(jar.read(name)[6:8], "big") for name in own)
        expected = 69 if entry["minecraft"].startswith("26.") else 65 if entry["minecraft"].startswith("1.21") or entry["minecraft"] in ("1.20.5", "1.20.6") else 61
        if max_class > expected:
            raise RuntimeError(f"JAR bytecode {max_class} exceeds this Minecraft runtime ({expected})")
        if entry["loader"] == "fabric":
            metadata = json.loads(jar.read("fabric.mod.json"))
            if metadata.get("id") != "renderscope" or metadata.get("environment") != "client" or metadata["depends"]["minecraft"].lstrip("=").strip() != entry["minecraft"]:
                raise RuntimeError("Fabric metadata does not target the exact client Minecraft version")
            mixins = [config if isinstance(config, str) else config["config"] for config in metadata.get("mixins", [])]
        else:
            descriptor = "META-INF/neoforge.mods.toml" if "META-INF/neoforge.mods.toml" in names else "META-INF/mods.toml"
            metadata = tomllib.loads(jar.read(descriptor).decode("utf-8"))
            if not any(mod["modId"] == "renderscope" for mod in metadata["mods"]):
                raise RuntimeError("Loader descriptor does not register RenderScope")
            dependency = next(value for value in metadata["dependencies"]["renderscope"] if value["modId"] == "minecraft")
            version = entry["minecraft"]
            allowed = {"[" + version + "]", "[1.20.1,1.20.2)"} if version == "1.20.1" else {"[" + version + "]"}
            if dependency["versionRange"] not in allowed or dependency.get("side") != "CLIENT":
                raise RuntimeError("Loader descriptor must declare the exact Minecraft client target")
            mixins = [name for name in names if name.endswith("mixins.json") and "/" not in name]
        for config in mixins:
            mixin = json.loads(jar.read(config))
            refmap = mixin.get("refmap")
            if refmap and refmap not in names:
                raise RuntimeError("Declared production Mixin refmap is absent: " + refmap)
            language = re.fullmatch(r"JAVA_(\d+)", mixin.get("compatibilityLevel", ""))
            if language:
                limit = int(language.group(1)) + 44
                package = mixin.get("package", "").replace(".", "/")
                for relative in mixin.get("mixins", []) + mixin.get("client", []) + mixin.get("server", []):
                    name = package + "/" + relative.replace(".", "/") + ".class"
                    if name not in names:
                        raise RuntimeError("Declared Mixin class is absent: " + name)
                    actual = int.from_bytes(jar.read(name)[6:8], "big")
                    if actual > limit:
                        raise RuntimeError(f"Mixin class {name} uses bytecode {actual}, above its declared {mixin['compatibilityLevel']}")
    results = ROOT / entry["testResults"]
    suites = [ET.parse(file).getroot() for file in results.glob("TEST-*.xml")]
    tests = sum(int(suite.attrib.get("tests", 0)) for suite in suites)
    failures = sum(int(suite.attrib.get(key, 0)) for suite in suites for key in ("failures", "errors"))
    if tests == 0 or failures:
        raise RuntimeError(f"No successful actual unit test results for {entry['loader']} {entry['minecraft']}")
    return {"minecraft": entry["minecraft"], "loader": entry["loader"], "loaderVersion": entry["loaderVersion"],
            "artifact": entry["artifact"], "sha256": digest(artifact), "bytes": artifact.stat().st_size,
            "unitTests": tests, "classMajor": max_class, "metadataAndLegalNoticesVerified": True}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--minecraft", nargs="+")
    parser.add_argument("--loader", nargs="+", choices=["fabric", "forge", "neoforge"])
    parser.add_argument("--java21")
    parser.add_argument("--java25")
    parser.add_argument("--audit-only", action="store_true")
    parser.add_argument("--package", action="store_true", help="Copy audited main JARs into distribution/0.3.0")
    args = parser.parse_args()
    selected = [entry for entry in targets() if (not args.minecraft or entry["minecraft"] in args.minecraft) and (not args.loader or entry["loader"] in args.loader)]
    if not args.all and not args.minecraft and not args.loader and not args.audit_only:
        for entry in selected:
            print(f"{entry['minecraft']:8} {entry['loader']:8} {entry['loaderVersion']:18} JDK{entry['java']} {entry['project']}")
        return
    if not selected:
        raise RuntimeError("No exact build targets match the requested selection")
    if args.minecraft:
        for version in args.minecraft:
            if not any(entry["minecraft"] == version for entry in selected):
                raise RuntimeError("No configured target for Minecraft " + version)
    for entry in selected:
        if not args.audit_only:
            major = entry["java"]
            env = os.environ.copy()
            home = java_home(major, getattr(args, f"java{major}"))
            env["JAVA_HOME"] = str(home)
            env["PATH"] = str(home / "bin") + os.pathsep + env.get("PATH", "")
            env[f"JAVA_HOME_{major}_X64"] = str(home)
            for alternate in (21, 25):
                if alternate == major:
                    continue
                try:
                    env[f"JAVA_HOME_{alternate}_X64"] = str(java_home(alternate, getattr(args, f"java{alternate}")))
                except RuntimeError:
                    if getattr(args, f"java{alternate}"):
                        raise
            wrapper = ROOT / entry["wrapper"]
            if os.name == "nt":
                wrapper = wrapper.with_name(wrapper.name + ".bat")
            command = ([str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)])
            command.extend(["-p", str(ROOT / entry["project"])])
            command.extend(entry.get("gradleArgs", []))
            command.append("-Porg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME_25_X64")
            command.extend(f"-P{key}={value}" for key, value in entry.get("properties", {}).items())
            command.extend(["test", "build", "--console=plain"])
            print(f"Building {entry['loader']} / Minecraft {entry['minecraft']}", flush=True)
            subprocess.run(command, cwd=ROOT, env=env, check=True)
        proof = audit(entry)
        if args.package:
            source = ROOT / entry["artifact"]
            destination = ROOT / "distribution/0.3.0" / entry["loader"] / source.name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            proof["packagedArtifact"] = destination.relative_to(ROOT).as_posix()
            destination.with_suffix(".sha256").write_text(proof["sha256"] + "  " + destination.name + "\n", encoding="ascii")
        print(json.dumps(proof, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Build/audit failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
