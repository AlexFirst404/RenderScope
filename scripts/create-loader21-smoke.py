"""Adapt the same real-world smoke scenario to native Forge/NeoForge 1.20.5–1.21.1 events."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
text = (ROOT / 'src/smoke/java/dev/renderscope/smoke/SmokeTest.java').read_text()
text = text.replace('package dev.renderscope.smoke;', 'package dev.renderscope.loadertest;')
for line in ['import net.minecraftforge.common.MinecraftForge;\n', 'import net.minecraftforge.event.TickEvent;\n', 'import net.minecraftforge.fml.common.Mod;\n']:
    text = text.replace(line, '')
text = text.replace('@Mod("renderscope_smoke")\npublic final class SmokeTest {', 'public final class NativeSmokeTest {')
start = text.index('    public SmokeTest() {')
end = text.index('    public static void tick()', start)
text = text[:start] + text[end:]
text = text.replace('mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD);',
                    'mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));')
text = text.replace('.getOrThrow(WorldPresets.FLAT).createWorldDimensions());',
                    '.getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);')
text = text.replace('Real Minecraft 1.20.1 client', 'Real Minecraft client')
text = text.replace('        RESULTS.addProperty("error", failure.toString());', '''        RESULTS.addProperty("error", failure.toString());
        try {
            ClientRuntime runtime = ClientRuntime.current();
            if (runtime != null) RESULTS.add("failurePerformance", GSON.toJsonTree(runtime.profiler().snapshot().performance()));
            for (Class<?> type : new Class<?>[]{Minecraft.class, net.minecraft.client.renderer.entity.EntityRenderDispatcher.class}) {
                var injected = java.util.Arrays.stream(type.getDeclaredMethods()).map(java.lang.reflect.Method::getName)
                        .filter(name -> name.contains("renderscope")).toList();
                RESULTS.add(type.getSimpleName() + "MixinMethods", GSON.toJsonTree(injected));
            }
            RESULTS.add("loadedMixinConfigs", GSON.toJsonTree(org.spongepowered.asm.mixin.Mixins.getConfigs().stream()
                    .map(Object::toString).toList()));
        } catch (Throwable diagnosticFailure) {
            RESULTS.addProperty("diagnosticError", diagnosticFailure.toString());
        }''')
text = text.replace('RESULTS.addProperty("runtimeLoaderVerified", true);', '''RESULTS.addProperty("runtimeLoaderVerified", true);
                    String actualMinecraft = net.minecraft.SharedConstants.getCurrentVersion().getName();
                    require(actualMinecraft.equals(System.getProperty("renderscope.expectedMinecraft", actualMinecraft)), "Wrong Minecraft runtime");
                    RESULTS.addProperty("minecraftVersion", actualMinecraft);
                    String environment = System.getProperty("renderscope.smoke.environment", "development");
                    RESULTS.addProperty("environment", environment);
                    Path actualPrimary = HarnessPlatform.primaryJar();
                    if (Files.isRegularFile(actualPrimary)) {
                        var digest = java.security.MessageDigest.getInstance("SHA-256");
                        try (var stream = Files.newInputStream(actualPrimary)) {
                            byte[] buffer = new byte[65536];
                            for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
                        }
                        RESULTS.addProperty("primaryJarSha256", java.util.HexFormat.of().formatHex(digest.digest()));
                    } else require(environment.equals("development"), "Installed smoke did not load the main mod from a regular JAR");''')
target = ROOT / 'loader21/src/smoke/java/dev/renderscope/loadertest/NativeSmokeTest.java'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(text, encoding='utf-8', newline='\n')
print(target.relative_to(ROOT))
