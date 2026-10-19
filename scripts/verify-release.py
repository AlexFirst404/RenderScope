"""Verify the distributable jar and persist the evidence from unit/game checks."""
import hashlib
import json
from pathlib import Path
import struct
from xml.etree import ElementTree
import zipfile

root = Path(__file__).resolve().parent.parent
candidates = [p for p in (root / "build/libs").glob("renderscope-neoforge-*.jar")
              if not p.name.endswith(("-slim.jar", "-sources.jar"))]
assert len(candidates) == 1, "Expected one bundled release jar"
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
    manifest = archive.read("META-INF/MANIFEST.MF").decode()
    assert "MixinConfigs: renderscope.mixins.json" in manifest
    refmap = json.loads(archive.read("renderscope.refmap.json"))
    assert refmap["mappings"], "Empty production remapping table"
    mixins = json.loads(archive.read("renderscope.mixins.json"))
    assert mixins["required"] and mixins["compatibilityLevel"] == "JAVA_17"
    assert "RenderTypeNameAccessor" in mixins["client"]
    assert not mixins.get("mixins") and not mixins.get("server"), "Unexpected server mixins"
    assert any("mixinextras-forge-0.4.1.jar" in name for name in names), "Missing bundled runtime"
    for name in names:
        if name.endswith(".class"):
            bytecode = archive.read(name)
            assert struct.unpack(">H", bytecode[6:8])[0] == 61, f"Unexpected bytecode version: {name}"
            if name.startswith("dev/renderscope/core/"):
                assert b"net/minecraft/" not in bytecode and b"net/minecraftforge/" not in bytecode

tests = failures = errors = 0
for path in (root / "build/test-results/test").glob("TEST-*.xml"):
    suite = ElementTree.parse(path).getroot()
    tests += int(suite.attrib["tests"])
    failures += int(suite.attrib["failures"])
    errors += int(suite.attrib["errors"])
assert tests >= 17 and failures == errors == 0, "Unit/privacy checks did not pass"
smoke = json.loads((root / "distribution/smoke-result.json").read_text(encoding="utf-8"))
assert smoke["success"], "The real Minecraft smoke test did not pass"
for name in smoke["screenshots"]:
    assert (root / "distribution/gallery" / name).is_file(), f"Missing gameplay screenshot {name}"
record = {
    "artifact": jar.name,
    "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
    "bytes": jar.stat().st_size,
    "bytecodeRelease": 17,
    "buildJdk": 21,
    "minecraftVersion": "1.20.1",
    "neoforgeVersion": "47.1.106",
    "unitAndPrivacyTests": tests,
    "testFailures": failures,
    "testErrors": errors,
    "productionMetadataRefmapAndBundledRuntimeVerified": True,
    "coreHasNoMinecraftLoaderDependencies": True,
    "developmentClientSmokeTest": "passed",
    "smokeCompletedAt": smoke["completedAt"],
    "realGameplayScreenshots": len(smoke["screenshots"]),
    "externalVanillaServerTest": "not run",
    "thirdPartyRendererCompatibilityMatrix": "not run",
}
destination = root / "distribution/verification.json"
destination.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
print(f"Verified {jar.name}: {tests} tests, actual client smoke test, bytecode/refmap/dependencies")
print(f"SHA-256 {record['sha256']}")
