"""Reuse every native MC26 world/UI assertion without depending on Fabric APIs."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "modern/src/gametest/java/dev/renderscope/modern/smoke/ModernClientSmoke.java").read_text(encoding="utf-8")
source = "\n".join(line for line in source.splitlines() if not line.startswith("import net.fabricmc."))
source = source.replace("ModernClientSmoke implements FabricClientGameTest", "NativeModernSmoke")
source = source.replace("@Override public void runTest(ClientGameTestContext context)", "public void runTest(NativeSmokeContext context)")
source = source.replace("ClientGameTestContext context", "NativeSmokeContext context")
source = source.replace("TestSingleplayerContext world", "NativeSmokeContext.TestWorld world")
source = source.replace("mc.screen", "NativeSmokePlatform.currentScreen(mc)")
source = source.replace("ModernSmokePlatform", "NativeSmokePlatform")
source = source.replace('FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString()', 'net.minecraft.SharedConstants.getCurrentVersion().id()')
source = source.replace('FabricLoader.getInstance().getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString()', 'dev.renderscope.client.LoaderInfo.current().version()')
source = source.replace('"fabric-development-client"', 'System.getProperty("renderscope.smoke.environment", dev.renderscope.client.LoaderInfo.current().name().toLowerCase(Locale.ROOT)+"-development-client")')
source = source.replace('results.addProperty("geometryAvailable", false);', 'results.addProperty("loader", dev.renderscope.client.LoaderInfo.current().name());\n        results.addProperty("geometryAvailable", false);')
source = source.replace('context.restoreDefaultGameOptions();', '''if ("installed-production".equals(results.get("environment").getAsString())) {
                Path loaded = NativeSmokeOrigin.mainArtifact();
                if (!Files.isRegularFile(loaded)) throw new IllegalStateException("Primary mod origin is not an installed JAR");
                results.addProperty("primaryJarSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loaded))));
            }
            context.restoreDefaultGameOptions();''')
old = ROOT / "loader26/build/generated/native-smoke/dev/renderscope/modern/NativeModernSmoke.java"
old.unlink(missing_ok=True)
target = ROOT / "loader26/build/generated/native-smoke/dev/renderscope/modern/smoke/NativeModernSmoke.java"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(source + "\n", encoding="utf-8")
print("Generated native-loader harness with all shared MC26 gameplay assertions.")
