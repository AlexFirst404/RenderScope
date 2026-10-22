"""Verify the selected modern artifact; runtime proof is opt-in and never inferred from compilation."""
import argparse
import hashlib
import importlib.util
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


def verify_runtime(target, artifact, runtime_support):
    """Consume the same exact-identity, linked-hash proofs as the release gate."""
    accepted = runtime_support.inspect_runtime(target, artifact["sha256"])
    require(accepted, "No hash-matched world result for the selected artifact")
    proof = accepted[0]
    path = REPO / proof["path"]
    runtime = json.loads(path.read_text(encoding="utf-8"))
    if runtime.get("resultFile"):
        path = path.parent / runtime["resultFile"]
        runtime = json.loads(path.read_text(encoding="utf-8"))
    require(runtime.get("artifactBytes") == artifact["bytes"], "Runtime artifact size differs")
    for field in ("entityAndBlockEntityExtractionAndSubmission", "f8NativeScreen", "pauseNativeButton",
                  "selectedGizmoBoxAndLabels", "realCrosshairInspector", "reportPrivacy", "disconnectClearsInstances",
                  "selectedInstanceOwnerDetails", "rendererClassCopy", "highlightNativeButton"):
        require(runtime.get(field) is True, f"Runtime assertion missing: {field}")
    require(runtime.get("captureSeconds", 0) >= 5 and runtime.get("captureFrames", 0) > 20, "Runtime capture too short")
    require(runtime.get("heatmapGizmoBoxes", 0) >= 3, "Runtime heatmap missing measured objects")
    screenshots = runtime.get("screenshots", [])
    require(len(screenshots) >= 13 and len(set(screenshots)) == len(screenshots), "Runtime screenshots incomplete or duplicated")
    for name in screenshots:
        require(isinstance(name, str) and Path(name).name == name and (path.parent / name).is_file(), f"Screenshot missing: {name}")
    if proof["environment"] == "installed-production":
        require(runtime.get("harness") == "modern-installed-v1", "Unknown installed Fabric harness")
        require(runtime.get("actualPrimaryOriginVerified") is True and runtime.get("runtimeLoaderVerified") is True,
                "Installed runtime did not verify the actual loaded primary origin and loader")
        require(runtime.get("primaryJarBytes") == artifact["bytes"], "Installed primary origin size differs")
        require(runtime.get("geometryAvailable") is False and runtime.get("gpuTimingAvailable") is False,
                "Modern renderer-state CPU proof must not claim unavailable geometry or GPU measurements")
        require(runtime.get("extractionScopes", 0) > 0 and runtime.get("submissionScopes", 0) > 0,
                "Installed runtime did not observe real extraction and submission scopes")
    return {"runtimeVerified": True, "runtimeScope": proof["environment"], "runtimeEvidence": proof}


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
        spec = importlib.util.spec_from_file_location("modern_verify_support", REPO / "scripts/generate-support-matrix.py")
        runtime_support = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runtime_support)
        targets = json.loads((ROOT / "targets.json").read_text(encoding="utf-8"))["targets"]
        target = next(target for target in targets if target["minecraft"] == args.minecraft)
        result.update(verify_runtime(target, result, runtime_support))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
