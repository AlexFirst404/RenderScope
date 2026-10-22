"""Verify separate production-Fabric QA artifacts without claiming runtime success."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def require(value, message):
    if not value:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=ROOT / "modern/validation/build-matrix.json")
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    baseline = {row["minecraft"]: row["sha256"] for row in baseline["artifacts"]} if "artifacts" in baseline else baseline
    targets = json.loads((ROOT / "modern/targets.json").read_text(encoding="utf-8"))["targets"]
    require(len(targets) == 8, "Expected all eight modern Fabric targets")
    proofs = []
    for target in targets:
        mc = target["minecraft"]
        mapped = mc.startswith("1.21.")
        primary = ROOT / target["artifact"]
        main_sha = hashlib.sha256(primary.read_bytes()).hexdigest()
        require(main_sha == baseline[mc], "Frozen primary JAR changed while adding QA: " + mc)
        harness = primary.with_name(primary.stem + "-installed-smoke-harness.jar")
        with zipfile.ZipFile(primary) as main, zipfile.ZipFile(harness) as qa:
            names = qa.namelist()
            classes = [name for name in names if name.endswith(".class")]
            prefix = "dev/renderscope/modern/smoke/"
            require(classes and all(name.startswith(prefix) for name in classes), "QA classes escaped their isolated package")
            require(not ({n.rpartition("/")[0] for n in classes} & {n.rpartition("/")[0] for n in main.namelist() if n.endswith(".class")}), "Main/QA class packages overlap")
            require(all(struct.unpack(">H", qa.read(name)[6:8])[0] == (65 if mapped else 69) for name in classes), "QA class language level differs from this runtime")
            require(all(b"net/fabricmc/fabric/api/client/gametest" not in qa.read(name) for name in classes), "Development game-test API leaked into installed QA")
            for name in ("FabricInstalledSmoke", "InstalledModernSmoke", "NativeSmokeContext", "NativeSmokePlatform", "NativeSmokeOrigin"):
                require(prefix + name + ".class" in names, "Missing real installed QA implementation: " + name)
            origin = qa.read(prefix + "NativeSmokeOrigin.class")
            assertion = qa.read(prefix + "InstalledModernSmoke.class")
            require(b"getOrigin" in origin and b"getPaths" in origin and b"isRegularFile" in origin, "QA does not inspect actual loader origin")
            require(all(value in assertion for value in (b"isDevelopmentEnvironment", b"isSameFile", b"actualPrimaryOriginVerified", b"primaryJarSha256")), "QA lacks production-origin/development-classpath guards")
            metadata = json.loads(qa.read("fabric.mod.json"))
            require(metadata["id"] == "renderscope_modern_installed_smoke" and metadata["environment"] == "client", "Incorrect separate QA mod identity")
            require(metadata["depends"]["minecraft"] == mc and metadata["depends"]["java"] == (">=21" if mapped else ">=25"), "QA metadata targets another Minecraft/Java")
            require(metadata["entrypoints"] == {"client": ["dev.renderscope.modern.smoke.FabricInstalledSmoke"]}, "QA must use only its real client-tick bridge")
            mixins = json.loads(qa.read("renderscope.installed-smoke.mixins.json"))
            expected = ["MouseInvoker"] if mapped else [] if mc == "26.3" else ["NativeMouseInvoker"]
            require(mixins["required"] is True and mixins["client"] == expected, "QA native-input mixins target another API family")
            require(mixins["compatibilityLevel"] == ("JAVA_21" if mapped else "JAVA_25"), "QA Mixin language level differs")
            if mapped:
                refmap = json.loads(qa.read(mixins["refmap"]))
                require(refmap["mappings"]["dev/renderscope/modern/smoke/mixin/MouseInvoker"]["onMove"].startswith("method_"), "QA Invoker was not remapped to the production namespace")
                require(b"net/minecraft/class_310" in qa.read(prefix + "NativeSmokeContext.class"), "Mapped QA still references named development Minecraft classes")
            proofs.append({"minecraft": mc, "loader": "fabric", "loaderVersion": target["loaderVersion"],
                           "primaryArtifact": target["artifact"], "primarySha256": main_sha, "primaryUnchanged": True,
                           "harnessArtifact": harness.relative_to(ROOT).as_posix(), "harnessBytes": harness.stat().st_size,
                           "harnessSha256": hashlib.sha256(harness.read_bytes()).hexdigest(), "packagesDisjoint": True,
                           "productionNamespace": "intermediary" if mapped else "official unobfuscated", "runtimeVerified": False})
    report = {"meaning": "Isolated installed-Fabric QA compilation/remap/origin guards only; no game was launched and no installed-world pass is inferred.", "artifacts": proofs}
    (ROOT / "modern/validation/installed-smoke-build-matrix.json").write_bytes((json.dumps(report, indent=2) + "\n").encode("utf-8"))
    print("All eight installed-Fabric QA artifacts verified; frozen primary SHA unchanged; no runtime claim.")


if __name__ == "__main__":
    main()
