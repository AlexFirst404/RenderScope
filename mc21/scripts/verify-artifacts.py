"""Record actual compiled artifacts and tests; gameplay validation is a separate check."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "mc21"

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_runtime(mc: str, artifact_sha: str, required: bool):
    evidence = ROOT / "distribution/mc21/fabric" / mc
    paths = [evidence / name for name in ("smoke-result.json", "installed-launch.json", "installed-verification.json")]
    if not any(path.exists() for path in paths):
        assert not required, f"{mc}: installed game validation is missing"
        return {"gameplayValidation": "not_run_in_this_module_task"}
    assert all(path.is_file() for path in paths), f"{mc}: incomplete installed-game evidence"
    result, launch, verified = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    for record in (result, launch, verified):
        assert record["minecraftVersion"] == mc and record["loader"] == "Fabric" and record["loaderVersion"] == "0.19.5"
    assert result["success"] is True and verified["success"] is True
    assert result["environment"] == "installed-production" and launch["developmentClasspath"] is False
    assert result["primaryJarSha256"] == verified["primaryJarSha256"] == artifact_sha
    assert launch["mods"][0]["id"] == "renderscope" and launch["mods"][0]["sha256"] == artifact_sha
    actual_api = next(entry["version"] for entry in launch["mods"] if entry["id"] == "fabric-api")
    assert verified["resultFile"] == paths[0].name and verified["resultSha256"] == sha256(paths[0])
    for key in ("runtimeLoaderVerified", "realEntityAndBlockEntitySamples", "fullModeGeometryAndRenderTypes",
                "timingOnlyFallback", "nativeGeometryStoppedInTimingOnly", "captureExportPrivacy",
                "reportLoaderMetadataVerified", "f8AndControlsRegistration", "pauseMenuButton",
                "highlightAndTrackState", "crosshairTargetsRealBlockEntity"):
        assert result.get(key) is True, (mc, key)
    assert result["captureFrames"] > 0 and result["captureDurationSeconds"] >= 4.8
    assert result["worldOverlayBoxesSubmitted"] > 0 and result["worldOverlayLabelsSubmitted"] > 0
    assert result["smallGuiLayout"] == "320x240"
    screenshots = result["screenshots"]
    assert len(screenshots) >= 28 and len(screenshots) == len(set(screenshots))
    for name in screenshots:
        assert Path(name).name == name
        path = evidence / "gallery" / name
        assert path.is_file() and path.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", (mc, name)
    capture_path = evidence / "smoke-capture-example.json"
    capture = json.loads(capture_path.read_text(encoding="utf-8"))
    assert capture["minecraftVersion"] == mc and capture["loader"] == "Fabric" and capture["loaderVersion"] == "0.19.5"
    assert capture["positionsIncluded"] is False and capture["frames"] > 0
    forbidden = {"position", "dimension", "identity", "uuid", "playerusername", "username", "serverip",
                 "serverhostname", "worldname", "chat", "absolutepath"}
    def private_keys(value):
        if isinstance(value, dict):
            for key, child in value.items():
                assert key.lower() not in forbidden, (mc, key)
                private_keys(child)
        elif isinstance(value, list):
            for child in value: private_keys(child)
    private_keys(capture)
    return {"gameplayValidation": "installed_production_passed", "gameplayEvidence": evidence.relative_to(ROOT).as_posix(),
            "runtimeFabricAPI": actual_api,
            "smokeResultSha256": sha256(paths[0]), "captureSha256": sha256(capture_path),
            "screenshots": len(screenshots), "completedAt": result["completedAt"]}


def verify_optional_profiles(mc: str, artifact_sha: str):
    evidence = ROOT / "distribution/mc21/fabric" / mc / "renderer-compat/iris"
    if mc != "1.21.8" or not (evidence / "installed-verification.json").is_file():
        return {"thirdPartyMatrix": "not_run"}
    result, verified, visual = [json.loads((evidence / name).read_text(encoding="utf-8"))
                                for name in ("smoke-result.json", "installed-verification.json", "visual-qa.json")]
    assert result["success"] is True and verified["success"] is True and visual["overallPassed"] is True
    assert result["primaryJarSha256"] == verified["primaryJarSha256"] == visual["primaryJarSha256"] == artifact_sha
    assert verified["resultSha256"] == visual["smokeResultSha256"] == sha256(evidence / "smoke-result.json")
    assert visual["captureSha256"] == sha256(evidence / "smoke-capture-example.json")
    assert result["minecraftVersion"] == mc and result["loader"] == "Fabric" and result["loaderVersion"] == "0.19.5"
    assert result["geometryPolicy"] == "iris-sodium-1.21.8-timing-shadow-v1"
    assert result["actualShaderShadowPassObserved"] is True
    assert result["expectedOptionalMods"] == {"sodium": "0.7.2+mc1.21.8", "iris": "1.9.6+mc1.21.8"}
    for key in ("realEntityAndBlockEntitySamples", "fullModeTimingWithPartialGeometry", "timingOnlyFallback",
                "captureExportPrivacy", "f8AndControlsRegistration", "pauseMenuButton", "highlightAndTrackState"):
        assert result.get(key) is True, key
    assert result["irisLinePipelineBinding"] == {"inspectedProfile": True, "registeredPipelines": 2, "failure": ""}
    assert result["nativeGeometryCoverage"]["status"] == "unavailable-uninspected-native-abi"
    assert visual["visualReview"]["boundingBoxesVisibleAndAligned"] is True
    for name, digest in visual["screenshotsSha256"].items():
        assert Path(name).name == name and sha256(evidence / "gallery" / name) == digest
    return {"thirdPartyMatrix": "one_pinned_iris_profile_passed", "optionalCompatibilityProfiles": [{
        "profile": "fabric-iris-1.21.8", "evidence": evidence.relative_to(ROOT).as_posix(),
        "geometryPolicy": result["geometryPolicy"], "nativeGeometry": "unavailable",
        "visualHighlights": "passed", "completedAt": result["completedAt"]}]}


def verify(mc: str, version: str, require_runtime: bool = False):
    build = MODULE / "build" / mc
    jar = build / "libs" / f"renderscope-fabric-{version}+mc{mc}.jar"
    log = ROOT / ".tools" / f"mc21-build-{mc}.log"
    if not log.is_file() or "BUILD SUCCESSFUL" not in log.read_text(encoding="utf-8", errors="replace"):
        raise AssertionError(f"{mc}: no successful build log for the last attempt")
    results = [ET.parse(path).getroot() for path in (build / "test-results/test").glob("TEST-*.xml")]
    tests = {key: sum(int(result.get(key, "0")) for result in results) for key in ("tests", "failures", "errors", "skipped")}
    assert tests["tests"] >= 63 and tests["failures"] == tests["errors"] == tests["skipped"] == 0, (mc, tests)
    assert jar.is_file(), jar
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        metadata = json.loads(archive.read("fabric.mod.json"))
        assert metadata["id"] == "renderscope" and metadata["environment"] == "client"
        assert metadata["version"] == f"{version}+mc{mc}"
        assert metadata["depends"]["minecraft"] == mc and metadata["depends"]["java"] == ">=21"
        assert metadata["license"] == "LicenseRef-RenderScope-Source-Available-1.0"
        assert not any("/smoke/" in name or "compat/CompatTest" in name for name in names), "Test harness in production JAR"
        assert "META-INF/mods.toml" not in names and "dev/renderscope/RenderScope.class" not in names
        assert not any("/Forge" in name and name.endswith(".class") for name in names)
        for optional in ("IrisBufferSourceMixin", "FastBufferBuilderMixin", "FlywheelBatchMixin", "MekanismDeferredRenderMixin"):
            assert f"dev/renderscope/mixin/{optional}.class" not in names, optional
        config = json.loads(archive.read("renderscope.fabric.mixins.json"))
        for name in config["client"]:
            entry = config["package"].replace(".", "/") + "/" + name + ".class"
            assert entry in names, entry
        for name in ("dev/renderscope/core/RenderProfiler.class", "dev/renderscope/client/ui/RenderScopeScreen.class"):
            assert struct.unpack(">H", archive.read(name)[6:8])[0] == 65, "Java21 bytecode expected"
        refmap = archive.read("renderscope.fabric.refmap.json").decode()
        assert "setupAndRender" in refmap
        assert ("endVertex" if mc.startswith("1.20.") else "beginVertex") in refmap
        if mc.startswith("1.21.") and int(mc.split(".")[-1]) >= 2:
            assert "createRenderState" in refmap, "Actual entity state extraction was not remapped"
            selectors = json.loads(refmap)["mappings"]["dev/renderscope/mixin/EntityRenderDispatcherMixin"]
            entity_selector = "render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/EntityRenderer;)V"
            assert entity_selector in selectors, "Extraction must target the private overload containing the actual invocation"
            if int(mc.split(".")[-1]) >= 5:
                state_selector = "render(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/EntityRenderer;)V"
                assert state_selector in selectors, "Submission must target the private render-state overload"
        if mc.startswith("1.21.") and int(mc.split(".")[-1]) >= 5:
            assert metadata["accessWidener"] == "renderscope.accesswidener"
            assert "dev/renderscope/client/world/WorldOverlay$GlState.class" not in names
            assert "Vec3" in refmap, "Camera-aware block entity invocation was not remapped"
        pack = json.loads(archive.read("pack.mcmeta"))["pack"]["pack_format"]
        vanilla = Path.home() / ".gradle/caches/fabric-loom" / mc / "minecraft-client.jar"
        if vanilla.is_file():
            with zipfile.ZipFile(vanilla) as original:
                assert pack == json.loads(original.read("version.json"))["pack_version"]["resource"]
        assert archive.read("META-INF/LICENSE") == (ROOT / "LICENSE").read_bytes()
    harness = build / "libs" / f"renderscope-fabric-{version}+mc{mc}-smoke-harness.jar"
    with zipfile.ZipFile(harness) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
        assert metadata["id"] == "renderscope_smoke" and metadata["depends"]["minecraft"] == mc
        assert metadata["environment"] == "client"
        frame_mapping = json.loads(archive.read("renderscope.smoke.refmap.json"))["mappings"]["dev/renderscope/fabric/smoke/mixin/SmokeFrameMixin"]["render"]
        assert ("(FJZ)V" if mc.startswith("1.20.") else "(Lnet/minecraft/class_9779;Z)V") in frame_mapping
    pipelines = mc.startswith("1.21.") and int(mc.split(".")[-1]) >= 5
    artifact_sha = sha256(jar)
    return {"minecraft": mc, "loader": "Fabric", "loaderVersion": "0.19.5", "status": "compiled_and_tested",
            "toolchain": {"java": 21, "gradle": "8.14.3" if pipelines else "8.8",
                          "loom": "1.10.5" if pipelines else "1.7.4"},
            "artifact": jar.relative_to(ROOT).as_posix(), "bytes": jar.stat().st_size,
            "sha256": artifact_sha, "tests": tests,
            **verify_runtime(mc, artifact_sha, require_runtime), **verify_optional_profiles(mc, artifact_sha)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("minecraft", nargs="+")
    parser.add_argument("--write", action="store_true", help="Write port-status.json with the actual compile and available gameplay evidence")
    parser.add_argument("--require-runtime", action="store_true", help="Require the exact production JAR to have passed the installed-game scenario")
    args = parser.parse_args()
    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    version = re.search(r"^mod_version=(.+)$", properties, re.MULTILINE).group(1).strip()
    records = [verify(mc, version, args.require_runtime) for mc in args.minecraft]
    if args.write:
        (MODULE / "port-status.json").write_text(json.dumps({"schemaVersion":1,"renderscopeVersion":version,
            "targets":records},indent=2)+"\n", encoding="utf-8")
    for record in records:
        print(record["minecraft"], "PASS", record["tests"]["tests"], "tests", record["sha256"])

if __name__ == "__main__": main()
