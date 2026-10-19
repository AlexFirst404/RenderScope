"""Verify the distributable jar and persist the evidence from unit/game checks."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
from xml.etree import ElementTree
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--loader", choices=("forge", "neoforge"), default="forge")
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
loader = args.loader
loader_name, loader_version = {"forge": ("Forge", "47.4.10"), "neoforge": ("NeoForge", "47.1.106")}[loader]
build = root / "build" / loader
distribution = root / "distribution" / "forge" if loader == "forge" else root / "distribution"
candidates = [p for p in (build / "libs").glob(f"renderscope-{loader}-*.jar")
              if not p.name.endswith(("-slim.jar", "-sources.jar"))]
assert len(candidates) == 1, f"Expected one bundled {loader_name} release jar in {build / 'libs'}"
jar = candidates[0]
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    for required in ("META-INF/mods.toml", "META-INF/MANIFEST.MF", "renderscope.refmap.json",
                     "renderscope.mixins.json", "META-INF/jarjar/metadata.json", "renderscope-logo.png"):
        assert required in names, f"Missing {required}"
    assert not any("smoke" in name.lower() or "/test/" in name for name in names), "Test code in release jar"
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
assert tests >= 17 and failures == errors == 0, "Unit/privacy checks did not pass"
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
    "coreHasNoMinecraftLoaderDependencies": True,
    "developmentClientSmokeTest": "passed",
    "smokeRuntimeLoaderAndVersionVerified": True,
    "smokeCompletedAt": smoke["completedAt"],
    "realGameplayScreenshots": len(smoke["screenshots"]),
    "externalVanillaServerTest": "not run",
    "thirdPartyRendererCompatibilityMatrix": "not run",
}
destination = distribution / "verification.json"
destination.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
print(f"Verified {jar.name}: {tests} tests, actual {loader_name} {loader_version} client smoke test, bytecode/refmap/dependencies")
print(f"SHA-256 {record['sha256']}")
