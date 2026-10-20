"""Verify the distributable jar and persist the evidence from unit/game checks."""
import argparse
from datetime import datetime
import hashlib
import io
import json
from pathlib import Path
import re
import struct
from xml.etree import ElementTree
import zipfile

LICENSE_ID = "LicenseRef-RenderScope-Source-Available-1.0"
LICENSE_NAME = "RenderScope Source-Available License 1.0"


def verify_license(root, archive):
    license_bytes = (root / "LICENSE").read_bytes()
    notices_bytes = (root / "THIRD_PARTY_NOTICES.md").read_bytes()
    assert license_bytes.decode("utf-8").splitlines()[0] == LICENSE_NAME, "Unexpected RenderScope license text"
    assert f"SPDX-License-Identifier: {LICENSE_ID}" in license_bytes.decode("utf-8"), "Missing custom license identifier"
    assert notices_bytes, "Empty third-party notices"
    for folder in (root / "distribution", root / "distribution/forge"):
        assert (folder / "LICENSE").read_bytes() == license_bytes, f"Stale distribution license: {folder.name}"
        assert (folder / "THIRD_PARTY_NOTICES.md").read_bytes() == notices_bytes, f"Stale distribution third-party notices: {folder.name}"
    for name, expected in (("META-INF/LICENSE", license_bytes), ("META-INF/THIRD_PARTY_NOTICES.md", notices_bytes)):
        assert archive.namelist().count(name) == 1, f"Missing or duplicate bundled legal notice: {name}"
        assert archive.read(name) == expected, f"Bundled legal notice differs from source: {name}"
    metadata = archive.read("META-INF/mods.toml").decode("utf-8")
    match = re.search(r'^\s*license\s*=\s*"([^"\r\n]+)"\s*$', metadata, re.MULTILINE)
    assert match and match.group(1) == LICENSE_ID, "RenderScope metadata must declare its custom source-available license, not MIT/All Rights Reserved"
    return {"id": LICENSE_ID, "name": LICENSE_NAME,
            "licenseSha256": hashlib.sha256(license_bytes).hexdigest(),
            "thirdPartyNoticesSha256": hashlib.sha256(notices_bytes).hexdigest(),
            "bundledTextsMatchSource": True, "bothDistributionCopiesMatchSource": True}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def private_capture_fields(value):
    forbidden = {"position", "coordinates", "dimension", "identity", "uuid", "entityid",
                 "playerusername", "username", "serverip", "serverhostname", "serveraddress",
                 "worldname", "chat", "absolutepath"}
    if isinstance(value, dict):
        for key, child in value.items():
            assert key.lower().replace("_", "") not in forbidden, f"Private field in compatibility capture: {key}"
            private_capture_fields(child)
    elif isinstance(value, list):
        for child in value:
            private_capture_fields(child)


def verify_flywheel_measurements(result):
    expected = {
        "motor": ("OrientedRotatingVisual", "FLYWHEEL_VISUAL_UPDATE"),
        "shaft": ("SingleAxisRotatingVisual", "FLYWHEEL_VISUAL_TICK"),
        "cogwheel": ("SingleAxisRotatingVisual", "FLYWHEEL_VISUAL_TICK"),
        "press": ("PressVisual", "FLYWHEEL_VISUAL_FRAME"),
    }
    for key in ("defaultInstancedVisualsFull", "defaultInstancedVisualsTimingOnly"):
        visuals = result[key]
        assert set(visuals) == set(expected), "Missing default-backend Flywheel mechanism measurements"
        for mechanism, (visual_class, source_name) in expected.items():
            row = visuals[mechanism]
            # BE registry types can be shared by several blocks (for example
            # shaft and cogwheel are create:simple_kinetic), not block IDs.
            assert row["type"].startswith("create:") and row["registryOwner"] == row["visualOwner"] == "create"
            assert row["type"] == result["defaultInstancedVisualsFull"][mechanism]["type"]
            assert visual_class in row["visualClass"], f"Wrong visual implementation for {mechanism}"
            statistics = row["statistics"]
            source = statistics["sources"][source_name]
            assert source["calls"] > 0 and source["averageMs"] > 0 and source["failures"] == 0, f"No real {source_name} timing for {mechanism}"
            assert statistics["verticesPerFrame"] == statistics["estimatedBytesPerFrame"] == 0, "Per-visual callbacks must not claim instanced GPU geometry"
            if key.endswith("TimingOnly"):
                assert not statistics["renderTypes"], "Timing Only retained visual geometry metadata"
    for field in ("defaultFlywheelVisualFrameCalls", "defaultFlywheelVisualTickCalls", "defaultFlywheelVisualUpdateCalls",
                  "defaultFlywheelTrackedVisuals", "defaultFlywheelSharedBatchCalls", "timingOnlyFlywheelVisualFrameCalls", "timingOnlyFlywheelVisualTickCalls"):
        assert result[field] > 0, f"Missing actual Flywheel callback activity: {field}"
    assert result["defaultFlywheelTrackedVisuals"] >= 4
    assert result["realPressHeadOffsetChanges"] >= 3 and result["realShaftSpeedChanges"] >= 2, "Create mechanisms did not actually animate"
    phase = result["flywheelSharedCpuPhase"]
    assert phase["id"] == "flywheel:batch_submit"
    phase_source = phase["stats"]["sources"]["FLYWHEEL_BATCH_SUBMIT"]
    assert phase_source["calls"] > 0 and phase_source["averageMs"] > 0 and phase_source["failures"] == 0
    gpu = result["defaultFlywheelGpuFull"]
    assert gpu["status"] == "available" and gpu["sampledFrames"] > 0 and gpu["completedBatches"] > 0
    assert 0 < gpu["averageMs"] <= gpu["peakMs"] and 0 <= gpu["pendingBatches"] <= 64 and gpu["droppedBatches"] >= 0
    timing_gpu = result["defaultFlywheelGpuTimingOnly"]
    assert timing_gpu["status"] == "disabled" and timing_gpu["completedBatches"] == timing_gpu["pendingBatches"] == 0, "Timing Only must not retain GPU query work"
    for mechanism in expected:
        assert result["instancedMechanismsInHeatmap"][mechanism] is True, f"World heatmap omitted {mechanism}"
    for field in ("createShaftSelectedInstanceBoxAndLabel", "createMotorSelectedInstanceBoxAndLabel", "createPressSelectedInstanceBoxAndLabel",
                  "removedVisualExpiredAndOverlayCleared", "recreatedVisualMeasured", "worldSessionMeasurementsAndSelectionCleared"):
        assert result.get(field) is True, f"Flywheel selection/lifecycle check failed: {field}"
    return {"perInstanceVisualCpuSources": {name: data[1] for name, data in expected.items()},
            "fullAndTimingOnlyVisualCpuVerified": True, "sharedCpuPhase": phase,
            "sharedBatchGpuFull": gpu, "timingOnlyGpuDisabled": True,
            "animatedMechanismsSelectedAndHeatmapped": True, "removeRecreateDisconnectCleanupVerified": True,
            "geometryScope": "Per-visual CPU callbacks have no per-object instanced vertex/GPU allocation; shared draw GPU time is measured separately."}


def verify_installed_compatibility(directory, primary_jar, primary_sha, renderscope_version):
    """Accept evidence only for this exact release JAR and installed client run."""
    result_path = directory / "compat-result.json"
    result = read_json(result_path)
    launch = read_json(directory / "installed-launch.json")
    verification = read_json(directory / "installed-verification.json")
    assert result.get("success") is True, "Installed Create/Mekanism checks failed"
    assert result.get("environment") == "installed-production" and result.get("primaryArtifactKind") == "jar", "Compatibility evidence is not an installed production JAR run"
    assert result.get("loader") == "Forge" and result.get("loaderVersion") == "47.4.10", "Wrong installed compatibility runtime"
    assert result.get("primaryJarSha256") == primary_sha, "Installed compatibility measured another RenderScope JAR"
    assert verification.get("success") is True, "Installed launcher result verification failed"
    assert verification.get("runtimeLoader") == "Forge" and verification.get("runtimeLoaderVersion") == "47.4.10"
    assert verification.get("primaryJarSha256") == primary_sha, "Installed launcher verified another JAR"
    assert verification.get("compatibilityResultSha256") == sha256(result_path), "Installed compatibility result changed after launcher verification"
    assert launch.get("clientLaunched") is True and launch.get("developmentClasspath") is False
    assert launch.get("minecraftVersion") == "1.20.1" and launch.get("loader") == "Forge" and launch.get("loaderVersion") == "47.4.10"
    assert launch.get("mainClass") == "cpw.mods.bootstraplauncher.BootstrapLauncher" and launch.get("launchTarget") == "forgeclient"
    assert launch.get("javaRuntime") == 21 and launch.get("vanillaClientBootstrapIgnored") is True
    assert launch.get("forgeInstallerSha1") == "66bfea9963bfa60d88bab6b2750e74a958392715"
    assert launch.get("vanillaClientSha1") == "0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838"
    timestamps = [launch["launchedAt"], result["startedAt"], result["completedAt"], verification["verifiedAt"]]
    moments = [datetime.fromisoformat(value.replace("Z", "+00:00")) for value in timestamps]
    assert moments == sorted(moments), "Installed evidence combines different or out-of-order client runs"

    pinned_mods = {
        "create-1.20.1-6.0.8.jar": "6fbb910c367dbce8e4fc7e5bf64b6edd4de980906ed00af8e47e4af843c0d9b0",
        "Mekanism-1.20.1-10.4.16.80.jar": "3b0d191fa4a45e662f9725991d76a30192f30186abb2ba694b873b5f7b9358d2",
    }
    staged = {row["file"]: row["sha256"] for row in launch["mods"]}
    assert len(staged) == len(launch["mods"]) == 4, "Installed compatibility profile must contain exactly four separate JARs"
    assert staged.get(primary_jar.name) == primary_sha, "Installed launcher staged a different release JAR"
    for name, expected_sha in pinned_mods.items():
        assert staged.get(name) == expected_sha, f"Installed compatibility loaded another vendor build: {name}"
    harness_name = primary_jar.stem + "-compat-harness.jar"
    assert harness_name in staged, "Missing separate compatibility harness in installed launch"
    harness = primary_jar.parent / harness_name
    assert harness.is_file() and sha256(harness) == staged[harness_name], "Installed evidence used another compatibility harness"
    with zipfile.ZipFile(harness) as archive:
        # This successful harness cannot reach capture or FINISH unless it has
        # restored Flywheel after the explicit fallback phase.
        code = archive.read("dev/renderscope/compat/CompatTest.class")
        assert b"Default Flywheel backend was not restored" in code, "Harness omitted the Flywheel restoration guard"
    versions = {row["id"]: row["version"] for row in result["testedMods"]}
    assert versions == {"create": "6.0.8", "mekanism": "10.4.16"}, "Wrong installed mod metadata versions"
    # Mekanism build80 declares 10.4.16 in mods.toml; the exact build is pinned
    # above by the vendor filename and SHA-256, rather than its display version.
    assert result["defaultFlywheelBackend"] == "flywheel:indirect" and result["fallbackFlywheelBackend"] == "flywheel:off"
    assert result["fallbackKineticVerticesPerFrame"] > 0 and result["observedCreateShaftRpm"] > 0 and result["realFluidAnimationUpdates"] > 0
    for phase, geometry in (("defaultFull", True), ("defaultTimingOnly", False), ("fallbackFull", True)):
        for mod in ("create", "mekanism"):
            row = result[phase][mod]
            assert row["type"].startswith(mod + ":") and row["rendererOwner"] == mod
            for field in ("modStatistics", "typeStatistics", "instanceStatistics"):
                statistics = row[field]
                assert statistics["calls"] > 0 and statistics["averageMs"] > 0 and statistics["failures"] == 0
                if geometry:
                    assert statistics["verticesPerFrame"] > 0 and statistics["estimatedBytesPerFrame"] > 0 and statistics["renderTypes"]
                else:
                    assert statistics["verticesPerFrame"] == statistics["estimatedBytesPerFrame"] == 0 and not statistics["renderTypes"]
    for prefix in ("default", "timingOnly"):
        measured = result[prefix + "DeferredRendererCalls"]
        assert measured > 0 and result[prefix + "QueuedDeferredCalls"] > 0 and result[prefix + "RenderedDeferredCalls"] >= measured
    cube = result["deferredEnergyCubeStatistics"]
    assert cube["calls"] > 0 and cube["averageMs"] > 0 and cube["verticesPerFrame"] > 0 and cube["renderTypes"]
    for field in ("captureExportMetadataAndPrivacy", "createSelectedInstanceBoxAndLabel", "mekanismSelectedInstanceBoxAndLabel"):
        assert result.get(field) is True, f"Installed compatibility check failed: {field}"
    assert result["combinedHeatmapBoxes"] >= 8
    flywheel = verify_flywheel_measurements(result)

    capture_path = directory / "compat-capture-example.json"
    capture = read_json(capture_path)
    assert capture["loader"] == "Forge" and capture["loaderVersion"] == "47.4.10" and capture["minecraftVersion"] == "1.20.1"
    assert capture["renderscopeVersion"] == renderscope_version and capture["positionsIncluded"] is False
    assert capture["measurementMode"] == "FULL" and capture["frames"] == result["captureFrames"] > 0
    assert capture["durationSeconds"] == result["captureDurationSeconds"] >= 4.8
    private_capture_fields(capture)
    for mod in ("create", "mekanism"):
        assert any(row["id"] == mod and row["statistics"]["calls"] > 0 for row in capture["mods"]), f"Installed capture omitted measured {mod} calls"
        assert any(row["id"] == mod and row["version"] == versions[mod] for row in capture["installedMods"]), f"Installed capture omitted {mod} metadata"
    for mechanism, source_name in flywheel["perInstanceVisualCpuSources"].items():
        registry = result["defaultInstancedVisualsFull"][mechanism]["type"]
        rows = [row for row in capture["objects"] if row["id"] == registry]
        assert rows and rows[0]["statistics"]["sources"][source_name]["calls"] > 0, f"Capture omitted visual source for {registry}"
    shared = next((row for row in capture["phases"] if row["id"] == "flywheel:batch_submit"), None)
    assert shared and shared["statistics"]["sources"]["FLYWHEEL_BATCH_SUBMIT"]["calls"] > 0, "Capture omitted shared Flywheel CPU phase"
    assert shared["statistics"]["averageMs"] > 0 and shared["statistics"]["verticesPerFrame"] == 0
    expected_images = {"01-create-mekanism-mods.png", "02-create-fluid-tank-detail.png", "03-mekanism-fluid-tank-detail.png",
                       "04-create-selected-instance.png", "05-mekanism-selected-instance.png", "06-create-mekanism-heatmap.png",
                       "07-compatibility-capture.png", "08-create-instanced-shaft-detail.png", "09-create-instanced-shaft-selected.png",
                       "10-create-instanced-motor-selected.png", "11-create-instanced-press-selected.png"}
    assert len(result["screenshots"]) == 11 and set(result["screenshots"]) == expected_images, "Incomplete installed compatibility screenshot evidence"
    screenshots = []
    for name in result["screenshots"]:
        path = directory / name
        image = path.read_bytes()
        assert image[:8] == b"\x89PNG\r\n\x1a\n" and image[12:16] == b"IHDR", f"Invalid installed gameplay PNG: {name}"
        width, height = struct.unpack(">II", image[16:24])
        assert width >= 320 and height >= 240, f"Invalid gameplay screenshot size: {name}"
        screenshots.append({"file": name, "sha256": sha256(path)})
    return {
        "status": "passed", "environment": result["environment"], "loader": result["loader"], "loaderVersion": result["loaderVersion"],
        "primaryJarSha256": primary_sha, "completedAt": result["completedAt"],
        "evidenceDirectory": "compatibility-installed", "resultSha256": sha256(result_path),
        "captureSha256": sha256(capture_path), "stagedArtifacts": launch["mods"], "testedMods": result["testedMods"],
        "realGameplayScreenshots": screenshots, "fullAndTimingOnlyMeasurements": True,
        "mekanismDeferredFullCalls": result["defaultDeferredRendererCalls"],
        "mekanismDeferredTimingOnlyCalls": result["timingOnlyDeferredRendererCalls"],
        "flywheelDefaultFallbackAndRestoration": "passed by installed harness", "bothSelectedInstanceBoxesAndLabels": True,
        "flywheelInstancing": flywheel,
        "captureExportMetadataAndPrivacy": True,
        "visualCpuSourcesAndSharedPhasesExported": True, "gpuDiagnosticsScope": "Live shared Flywheel batch diagnostics; not exported as capture or attributed to instances.",
        "scope": "Pinned Create6.0.8/Flywheel1.0.5 and Mekanism10.4.16.80 fixtures; per-visual CPU callbacks plus shared batch CPU/GPU phases. No per-object GPU attribution or separate Create contraption virtual-world block entity attribution.",
    }

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--loader", choices=("forge", "neoforge"), default="forge")
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
properties = dict(re.findall(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\S+)\s*$", (root / "gradle.properties").read_text(), re.MULTILINE))
current_version = properties["mod_version"] + "+mc" + properties["minecraft_version"]
loader = args.loader
loader_name, loader_version = {"forge": ("Forge", "47.4.10"), "neoforge": ("NeoForge", "47.1.106")}[loader]
build = root / "build" / loader
distribution = root / "distribution" / "forge" if loader == "forge" else root / "distribution"
jar = build / "libs" / f"renderscope-{loader}-{current_version}.jar"
assert jar.is_file(), f"Missing current bundled {loader_name} release jar: {jar}"
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    legal = verify_license(root, archive)
    for required in ("META-INF/mods.toml", "META-INF/MANIFEST.MF", "renderscope.refmap.json",
                     "renderscope.mixins.json", "META-INF/jarjar/metadata.json", "renderscope-logo.png"):
        assert required in names, f"Missing {required}"
    assert not any("smoke" in name.lower() or "/test/" in name or "dev/renderscope/compat/" in name for name in names), "Test code in release jar"
    metadata = archive.read("META-INF/mods.toml").decode()
    assert "${" not in metadata, "Unexpanded release metadata"
    assert 'modId="renderscope"' in metadata and 'displayTest="IGNORE_ALL_VERSION"' in metadata
    assert '[1.20.1,1.20.2)' in metadata
    assert f'versionRange="[{loader_version},48)"' in metadata, "Wrong target loader dependency range"
    assert f"Built for {loader_name} {loader_version}, Minecraft 1.20.1." in metadata, "Wrong target loader description"
    manifest = archive.read("META-INF/MANIFEST.MF").decode()
    assert "MixinConfigs: renderscope.mixins.json" in manifest
    manifest_fields = dict(line.split(": ", 1) for line in manifest.replace("\r\n ", "").splitlines() if ": " in line)
    assert manifest_fields.get("RenderScope-Loader") == loader, "JAR manifest belongs to another loader"
    assert manifest_fields.get("RenderScope-Loader-Version") == loader_version, "JAR manifest targets another loader version"
    refmap = json.loads(archive.read("renderscope.refmap.json"))
    assert refmap["mappings"], "Empty production remapping table"
    mixins = json.loads(archive.read("renderscope.mixins.json"))
    assert mixins["required"] and mixins["compatibilityLevel"] == "JAVA_17"
    assert "RenderTypeNameAccessor" in mixins["client"]
    assert not mixins.get("mixins") and not mixins.get("server"), "Unexpected server mixins"
    bundled_runtime = json.loads(archive.read("META-INF/jarjar/metadata.json"))
    runtime_entry = next((entry for entry in bundled_runtime["jars"]
                          if entry["identifier"]["artifact"] == "mixinextras-forge"), None)
    assert runtime_entry and runtime_entry["version"]["artifactVersion"] == "0.4.1", "Missing bundled MixinExtras runtime"
    with zipfile.ZipFile(io.BytesIO(archive.read(runtime_entry["path"]))) as runtime:
        common_entry = json.loads(runtime.read("META-INF/jarjar/metadata.json"))["jars"][0]
        with zipfile.ZipFile(io.BytesIO(runtime.read(common_entry["path"]))) as common:
            for required in ("com/llamalad7/mixinextras/MixinExtrasBootstrap.class",
                             "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation.class"):
                assert required in common.namelist(), f"Missing bundled runtime class {required}"
    for name in names:
        if name.endswith(".class"):
            bytecode = archive.read(name)
            assert struct.unpack(">H", bytecode[6:8])[0] == 61, f"Unexpected bytecode version: {name}"
            if name.startswith("dev/renderscope/core/"):
                assert all(prefix not in bytecode for prefix in
                           (b"net/minecraft/", b"net/minecraftforge/", b"net/neoforged/"))

tests = failures = errors = 0
for path in (build / "test-results/test").glob("TEST-*.xml"):
    suite = ElementTree.parse(path).getroot()
    tests += int(suite.attrib["tests"])
    failures += int(suite.attrib["failures"])
    errors += int(suite.attrib["errors"])
assert tests >= 36 and failures == errors == 0, "Current renderer/visual/scheduling/privacy checks did not pass"
smoke = json.loads((distribution / "smoke-result.json").read_text(encoding="utf-8"))
assert smoke["success"], "The real Minecraft smoke test did not pass"
capture = json.loads((distribution / "smoke-capture-example.json").read_text(encoding="utf-8"))
assert capture["loader"] == loader_name, f"Smoke capture belongs to {capture['loader']}, expected {loader_name}"
assert capture["loaderVersion"] == loader_version, "Smoke capture was recorded using the wrong loader version"
assert capture["minecraftVersion"] == "1.20.1", "Smoke capture used the wrong Minecraft version"
assert capture["renderscopeVersion"] == jar.name.removeprefix(f"renderscope-{loader}-").removesuffix(".jar"), "Smoke capture used a different RenderScope version"
assert capture["positionsIncluded"] is False, "Smoke capture must demonstrate the private export default"
assert capture["frames"] > 0, "Empty smoke capture"
for field in ("realEntityAndBlockEntitySamples", "fullModeGeometryAndRenderTypes", "captureExportPrivacy",
              "timingOnlyFallback", "f8AndControlsRegistration", "pauseMenuButton", "highlightAndTrackState",
              "runtimeLoaderVerified", "reportLoaderMetadataVerified"):
    assert smoke.get(field) is True, f"Smoke check did not pass: {field}"
assert smoke["loader"] == loader_name, "Smoke result loader does not match the selected target"
assert smoke["loaderVersion"] == loader_version, "Smoke result loader version does not match the selected target"
for name in smoke["screenshots"]:
    assert Path(name).name == name, "Screenshot evidence must be inside the selected platform gallery"
    assert (distribution / "gallery" / name).is_file(), f"Missing gameplay screenshot {name}"
record = {
    "artifact": jar.name,
    "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
    "bytes": jar.stat().st_size,
    "bytecodeRelease": 17,
    "buildJdk": 21,
    "minecraftVersion": "1.20.1",
    "loader": loader_name,
    "loaderVersion": loader_version,
    f"{loader}Version": loader_version,
    "unitAndPrivacyTests": tests,
    "testFailures": failures,
    "testErrors": errors,
    "productionMetadataRefmapAndBundledRuntimeVerified": True,
    "license": legal,
    "coreHasNoMinecraftLoaderDependencies": True,
    "developmentClientSmokeTest": "passed",
    "smokeRuntimeLoaderAndVersionVerified": True,
    "smokeCompletedAt": smoke["completedAt"],
    "realGameplayScreenshots": len(smoke["screenshots"]),
    "externalVanillaServerTest": "not run",
    "thirdPartyRendererCompatibilityMatrix": "not run",
}
installed_evidence = distribution / "compatibility-installed"
if loader == "forge" and installed_evidence.exists():
    record["installedProductionClientCompatibility"] = verify_installed_compatibility(
        installed_evidence, jar, record["sha256"], capture["renderscopeVersion"])
    record["thirdPartyRendererCompatibilityMatrix"] = "passed pinned pair: Create6.0.8 + Mekanism10.4.16.80 on Forge47.4.10; see compatibility-installed"
destination = distribution / "verification.json"
destination.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
print(f"Verified {jar.name}: {tests} tests, actual {loader_name} {loader_version} client smoke test, bytecode/refmap/dependencies")
print(f"SHA-256 {record['sha256']}")
