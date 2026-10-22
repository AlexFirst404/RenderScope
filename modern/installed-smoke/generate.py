"""Generate isolated installed-Fabric QA from the unchanged real gameplay assertions."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--minecraft", choices=("1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"), required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
output = args.output.resolve()
if not output.is_relative_to((ROOT / "modern").resolve()) or "build" not in output.parts:
    raise SystemExit("Generated installed QA must remain in a modern build directory")
mapped = args.minecraft.startswith("1.21.")
source = (ROOT / "modern/src/gametest/java/dev/renderscope/modern/smoke/ModernClientSmoke.java").read_text(encoding="utf-8")
source = "\n".join(line for line in source.splitlines() if not line.startswith("import net.fabricmc.fabric.api.client.gametest."))

def replace(before, after):
    global source
    if before not in source:
        raise SystemExit("Shared real-world QA contract changed: " + before)
    source = source.replace(before, after)

replace("ModernClientSmoke implements FabricClientGameTest", "InstalledModernSmoke")
replace("@Override public void runTest(ClientGameTestContext context)", "public void runTest(NativeSmokeContext context)")
replace("ClientGameTestContext context", "NativeSmokeContext context")
replace("TestSingleplayerContext world", "NativeSmokeContext.TestWorld world")
replace("ModernSmokePlatform", "NativeSmokePlatform")
replace('"fabric-development-client"', 'System.getProperty("renderscope.modern.smoke.environment", "missing-installed-scope")')
replace('Path artifact = Path.of(System.getProperty("renderscope.modern.smoke.artifact"));', '''require("installed-production".equals(results.get("environment").getAsString()), "Installed QA requires explicit installed scope");
            require(!FabricLoader.getInstance().isDevelopmentEnvironment(), "Installed QA must not run on a development classpath");
            require(results.get("minecraftVersion").getAsString().equals(System.getProperty("renderscope.expectedMinecraft")), "Actual Minecraft differs from requested runtime");
            require(results.get("loaderVersion").getAsString().equals(System.getProperty("renderscope.expectedLoaderVersion")), "Actual Fabric Loader differs from requested runtime");
            require("Fabric".equals(System.getProperty("renderscope.expectedLoader")), "Requested loader is not Fabric");
            results.addProperty("loader", "Fabric");
            results.addProperty("harness", "modern-installed-v1");
            results.addProperty("runtimeLoaderVerified", true);
            Path requested = Path.of(System.getProperty("renderscope.modern.smoke.artifact"));
            Path artifact = NativeSmokeOrigin.primaryJar();
            require(Files.isRegularFile(artifact) && Files.isSameFile(artifact, requested), "Actual loaded primary JAR differs from staged requested JAR");
            results.addProperty("actualPrimaryOriginVerified", true);''')
replace('results.addProperty("artifactSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(artifactBytes)));', '''results.addProperty("artifactSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(artifactBytes)));
            results.addProperty("primaryJarSha256", results.get("artifactSha256").getAsString());
            results.addProperty("primaryJarBytes", artifactBytes.length);''')
if "ClientGameTestContext" in source or "FabricClientGameTest" in source or "fabric-development-client" in source:
    raise SystemExit("Incomplete installed QA adaptation")

package = output / "java/dev/renderscope/modern/smoke"
package.mkdir(parents=True, exist_ok=True)
(package / "InstalledModernSmoke.java").write_text(source + "\n", encoding="utf-8")
template = ROOT / ("loader-mapped/smoke/java" if mapped else "loader26/smoke/java")
for path in template.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    if mapped:
        text = text.replace('@Invoker(value="onMove",remap=false)', '@Invoker("onMove")')
        if args.minecraft == "1.21.11":
            text = text.replace("net.minecraft.world.level.GameRules(", "net.minecraft.world.level.gamerules.GameRules(")
    text = text.replace("new NativeModernSmoke()", "new InstalledModernSmoke()")
    destination = output / "java" / path.relative_to(template)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text, encoding="utf-8")
if not mapped:
    family = "sdl" if args.minecraft == "26.3" else "glfw" if args.minecraft.startswith("26.1") else "extractor-glfw"
    path = ROOT / f"loader26/smoke/{family}/dev/renderscope/modern/smoke/NativeSmokePlatform.java"
    (package / "NativeSmokePlatform.java").write_bytes(path.read_bytes())
resources = output / "resources"
resources.mkdir(parents=True, exist_ok=True)
mouse = ["MouseInvoker"] if mapped else [] if args.minecraft == "26.3" else ["NativeMouseInvoker"]
mixins = {"required": True, "minVersion": "0.8", "package": "dev.renderscope.modern.smoke.mixin",
          "compatibilityLevel": "JAVA_21" if mapped else "JAVA_25", "client": mouse, "injectors": {"defaultRequire": 1}}
if mapped:
    mixins["refmap"] = "renderscope.installed-smoke.refmap.json"
(resources / "renderscope.installed-smoke.mixins.json").write_text(json.dumps(mixins, indent=2) + "\n", encoding="utf-8")
metadata = {"schemaVersion": 1, "id": "renderscope_modern_installed_smoke", "version": "1.0.0",
            "name": "RenderScope isolated installed-client QA", "environment": "client",
            "entrypoints": {"client": ["dev.renderscope.modern.smoke.FabricInstalledSmoke"]},
            "mixins": ["renderscope.installed-smoke.mixins.json"],
            "depends": {"minecraft": args.minecraft, "java": ">=21" if mapped else ">=25",
                        "fabricloader": ">=0.19.5", "fabric-api": "*", "renderscope": ">=0.3.0"}}
(resources / "fabric.mod.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print("Generated isolated installed Fabric QA for " + args.minecraft + "; all original gameplay assertions preserved")
