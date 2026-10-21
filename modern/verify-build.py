"""Verify the selected modern artifact; runtime proof is opt-in and never inferred from compilation."""
import argparse
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
SUPPORTED = ("1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3")


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", choices=SUPPORTED, default="26.3")
    parser.add_argument("--require-runtime", action="store_true")
    args = parser.parse_args()
    mapped = args.minecraft.startswith("1.21.")
    project = ROOT / "mapped" if mapped else ROOT
    build = project / "build" / args.minecraft
    artifacts = list((build / "libs").glob(f"renderscope-fabric-*+mc{args.minecraft}.jar"))
    artifacts = [path for path in artifacts if not path.name.endswith("-sources.jar")]
    require(len(artifacts) == 1, f"Expected one selected production JAR, found {len(artifacts)}")
    artifact = artifacts[0]
    with zipfile.ZipFile(artifact) as jar:
        names = set(jar.namelist())
        metadata = json.loads(jar.read("fabric.mod.json"))
        require(metadata["id"] == "renderscope" and metadata["environment"] == "client", "Incorrect client metadata")
        require(metadata["depends"]["minecraft"] == args.minecraft, "Artifact targets another Minecraft version")
        require(metadata["depends"]["java"] == (">=21" if mapped else ">=25"), "Incorrect Java requirement")
        require(metadata["license"] == "LicenseRef-RenderScope-Source-Available-1.0", "Incorrect license metadata")
        require(jar.read("META-INF/LICENSE") == (REPO / "LICENSE").read_bytes(), "Embedded license differs from root")
        require(jar.read("META-INF/THIRD_PARTY_NOTICES.md") == (REPO / "THIRD_PARTY_NOTICES.md").read_bytes(), "Third-party notices differ")
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "")
        require(f"RenderScope-Minecraft: {args.minecraft}" in manifest, "Manifest target mismatch")
        require("RenderScope-Loader: fabric" in manifest, "Manifest loader mismatch")
        require(any(name.startswith("META-INF/jars/mixinextras-fabric-") for name in names), "MixinExtras runtime missing")
        require(not any("/smoke/" in name or "gametest" in name.lower() for name in names), "Test harness leaked into production artifact")
        for name in names:
            if name.endswith(".class") and not name.startswith("META-INF/"):
                require(struct.unpack(">H", jar.read(name)[6:8])[0] == (65 if mapped else 69), f"Unexpected class version: {name}")
        for name in ("dev/renderscope/core/DiagnosticEngine.class", "dev/renderscope/modern/RenderScopeScreen.class",
                     "dev/renderscope/modern/mixin/EntityDispatcherMixin.class", "dev/renderscope/modern/mixin/BlockEntityDispatcherMixin.class"):
            require(name in names, f"Missing implementation: {name}")
        mixins = json.loads(jar.read("renderscope.modern.mixins.json"))
        expected_world = "MappedLevelRendererMixin" if mapped else "LegacyLevelRendererMixin" if args.minecraft.startswith("26.1") else "LevelExtractorMixin"
        require(expected_world in mixins["client"] and mixins.get("required") is True, "Wrong world extraction mixin selection")
        require(jar.read("assets/renderscope/renderscope-logo.png") == (REPO / "src/main/resources/renderscope-logo.png").read_bytes(), "Original pixel-art logo changed")

    test_files = list((build / "test-results/test").glob("TEST-*.xml"))
    require(test_files, "No selected-version test XML")
    tests = failures = errors = skipped = 0
    for path in test_files:
        suite = ET.parse(path).getroot()
        tests += int(suite.attrib.get("tests", 0))
        failures += int(suite.attrib.get("failures", 0))
        errors += int(suite.attrib.get("errors", 0))
        skipped += int(suite.attrib.get("skipped", 0))
    require(tests >= 55 and failures == errors == skipped == 0, f"Tests: {tests}, failures: {failures}, errors: {errors}, skipped: {skipped}")

    result = {"minecraft": args.minecraft, "artifact": str(artifact.relative_to(REPO)).replace("\\", "/"),
              "bytes": artifact.stat().st_size, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
              "tests": tests, "runtimeVerified": False}
    if args.require_runtime:
        evidence = project / "validation" / args.minecraft
        runtime = json.loads((evidence / "smoke-result.json").read_text(encoding="utf-8"))
        require(runtime.get("success") is True and runtime.get("minecraftVersion") == args.minecraft, "Matching runtime did not pass")
        require(runtime.get("artifactSha256") == result["sha256"] and runtime.get("artifactBytes") == result["bytes"], "Runtime evidence belongs to another artifact")
        require(runtime.get("environment") == "fabric-development-client", "Unknown runtime scope")
        for field in ("entityAndBlockEntityExtractionAndSubmission", "f8NativeScreen", "pauseNativeButton",
                      "selectedGizmoBoxAndLabels", "realCrosshairInspector", "reportPrivacy", "disconnectClearsInstances",
                      "selectedInstanceOwnerDetails", "rendererClassCopy", "highlightNativeButton"):
            require(runtime.get(field) is True, f"Runtime assertion missing: {field}")
        require(runtime.get("captureSeconds", 0) >= 5 and runtime.get("captureFrames", 0) > 20, "Runtime capture too short")
        require(runtime.get("heatmapGizmoBoxes", 0) >= 3, "Runtime heatmap missing measured objects")
        require(len(runtime.get("screenshots", [])) >= 13, "Runtime screenshots incomplete")
        for name in runtime["screenshots"]:
            require(Path(name).name == name and (evidence / name).is_file(), f"Screenshot missing: {name}")
        result["runtimeVerified"] = True
        result["runtimeScope"] = "Fabric development-client real integrated world; not an installed-production test"
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
