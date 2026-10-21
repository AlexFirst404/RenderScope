"""Generate the exact build/evidence catalogue consumed by the repository matrix tools."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "mc21"
build = (MODULE / "build.gradle").read_text(encoding="utf-8")
properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
version = re.search(r"^mod_version=(.+)$", properties, re.MULTILINE).group(1).strip()
api_versions = dict(re.findall(r"'(1\.(?:20|21)(?:\.\d+)?)'\s*:\s*'([^']+)'", build))
versions = ["1.20.5", "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
            "1.21.5", "1.21.6", "1.21.7", "1.21.8"]
targets = []
for minecraft in versions:
    pipeline = minecraft.startswith("1.21.") and int(minecraft.split(".")[-1]) >= 5
    wrapper = "mc21/gradlew" if pipeline else "gradlew"
    artifact = f"mc21/build/{minecraft}/libs/renderscope-fabric-{version}+mc{minecraft}"
    targets.append({
        "id": "fabric-" + minecraft,
        "minecraft": minecraft,
        "loader": "fabric",
        "loaderVersion": "0.19.5",
        "fabricAPI": api_versions[minecraft],
        "java": 21,
        "gradleVersion": "8.14.3" if pipeline else "8.8",
        "loomVersion": "1.10.5" if pipeline else "1.7.4",
        "wrapper": wrapper,
        "wrapperWindows": wrapper + ".bat",
        "project": "mc21",
        "buildFile": "mc21/build.gradle",
        "properties": {"minecraft_version": minecraft},
        "tasks": ["test", "build", "remapSmokeJar"],
        "artifact": artifact + ".jar",
        "artifactPattern": f"mc21/build/{minecraft}/libs/renderscope-fabric-{{version}}+mc{minecraft}.jar",
        "harness": artifact + "-smoke-harness.jar",
        "testResults": f"mc21/build/{minecraft}/test-results/test",
        "runtimeEvidence": [f"distribution/mc21/fabric/{minecraft}/installed-verification.json",
                            f"distribution/mc21/fabric/{minecraft}/smoke-result.json"],
        "runtimeEvidenceDirectory": f"distribution/mc21/fabric/{minecraft}",
        "runtimeEnvironment": "installed-production",
        "gameDirectory": "run-fabric-smoke-" + minecraft,
        "verificationStatusPath": "mc21/port-status.json",
    })
document = {"schemaVersion": 1, "module": "mc21", "renderscopeVersion": version,
            "versionSource": "gradle.properties#mod_version", "targets": targets}
(MODULE / "targets.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
print(f"Wrote {len(targets)} exact Fabric targets to mc21/targets.json")
