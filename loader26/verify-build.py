"""Audit the actual native MC26 main/QA artifacts and their Mixin language levels."""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("build_all", ROOT / "scripts/build-all.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def main() -> None:
    proofs = []
    for target in builder.targets():
        if target["loader"] == "fabric" or not target["minecraft"].startswith("26."):
            continue
        proof = builder.audit(target)
        primary = ROOT / target["artifact"]
        harness = ROOT / target["harnessArtifact"]
        with zipfile.ZipFile(primary) as jar, zipfile.ZipFile(harness) as qa:
            names = jar.namelist()
            main_classes = [name for name in names if name.startswith("dev/renderscope/") and name.endswith(".class")]
            qa_classes = [name for name in qa.namelist() if name.endswith(".class")]
            if "fabric.mod.json" in names or any(name.startswith("net/fabricmc/") for name in names):
                raise RuntimeError("Native main artifact contains Fabric runtime/metadata")
            if not qa_classes or any(not name.startswith("dev/renderscope/modern/smoke/") for name in qa_classes):
                raise RuntimeError("Native QA artifact contains stale or unisolated classes")
            main_packages = {name.rsplit("/", 1)[0] for name in main_classes}
            qa_packages = {name.rsplit("/", 1)[0] for name in qa_classes}
            if main_packages & qa_packages:
                raise RuntimeError("Main and QA artifacts have JPMS split packages")
            for archive, classes in ((jar, main_classes), (qa, qa_classes)):
                if any(int.from_bytes(archive.read(name)[6:8], "big") > 65 for name in classes):
                    raise RuntimeError("Native MC26 classes exceed the upstream Mixin Java 21 language level")
            main_config = json.loads(jar.read("renderscope.modern.mixins.json"))
            qa_config = json.loads(qa.read("renderscope.native-smoke.mixins.json"))
            expected_format = [84, 0] if target["minecraft"].startswith("26.1") else [88, 0] if target["minecraft"] == "26.2" else [97, 1]
            for archive in (jar, qa):
                pack = json.loads(archive.read("pack.mcmeta"))["pack"]
                if pack.get("min_format") != expected_format or pack.get("max_format") != expected_format:
                    raise RuntimeError("Pack metadata does not match actual Minecraft resource_major/minor")
            if main_config["compatibilityLevel"] != "JAVA_21" or qa_config["compatibilityLevel"] != "JAVA_21":
                raise RuntimeError("Native Mixin configuration does not match the official loader language level")
            expected_mouse = [] if target["minecraft"] == "26.3" else ["NativeMouseInvoker"]
            if qa_config.get("client") != expected_mouse:
                raise RuntimeError("QA mouse invoker targets the wrong GLFW/SDL API family")
            descriptor = "META-INF/neoforge.mods.toml" if target["loader"] == "neoforge" else "META-INF/mods.toml"
            metadata = tomllib.loads(qa.read(descriptor).decode("utf-8"))
            if [mod["modId"] for mod in metadata["mods"]] != ["renderscope_native_smoke"]:
                raise RuntimeError("QA artifact contains the wrong mod identity")
            mc = next(item for item in metadata["dependencies"]["renderscope_native_smoke"] if item["modId"] == "minecraft")
            if mc["versionRange"] != "[" + target["minecraft"] + "]" or mc["side"] != "CLIENT":
                raise RuntimeError("QA artifact does not target the exact Minecraft client")
        proof.update({"harnessArtifact": target["harnessArtifact"],
            "harnessSha256": hashlib.sha256(harness.read_bytes()).hexdigest(),
            "harnessBytes": harness.stat().st_size, "gameRuntimeJava": 25,
            "mixinCompatibilityLevel": "JAVA_21", "mainQaPackagesDisjoint": True,
            "mainContainsNoFabricRuntime": True})
        proofs.append(proof)
    if len(proofs) != 10:
        raise RuntimeError("Expected all ten native MC26 targets")
    output = ROOT / "loader26/validation/build-matrix.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"verifiedAt": datetime.now(timezone.utc).isoformat(),
        "meaning": "Actual build and packaging checks; installed-world evidence is recorded separately.",
        "targets": proofs}, indent=2) + "\n", encoding="utf-8")
    print("All ten native MC26 main/QA artifacts, real unit results, language levels and package isolation verified.")


if __name__ == "__main__":
    main()
