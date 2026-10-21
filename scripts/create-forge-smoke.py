"""Generate the installed Forge vanilla-world harness from the shared Fabric smoke scenario."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'fabric/src/smoke/java/dev/renderscope/fabric/smoke/FabricSmokeTest.java').read_text(encoding='utf-8')
source = source.replace('package dev.renderscope.fabric.smoke;', 'package dev.renderscope.smoke;')
source = source.replace('import net.fabricmc.api.ClientModInitializer;', 'import net.minecraftforge.fml.common.Mod;')
source = source.replace('import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;',
    'import net.minecraftforge.common.MinecraftForge;\nimport net.minecraftforge.event.TickEvent;')
source = source.replace('public final class FabricSmokeTest implements ClientModInitializer {',
    '@Mod("renderscope_smoke")\npublic final class InstalledSmokeTest {')
start = source.index('    @Override public void onInitializeClient() {')
end = source.index('    public static void tick()', start)
source = source[:start] + '''    public InstalledSmokeTest() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) tick();
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) frame();
        });
    }

''' + source[end:]
start = source.index('                    var primaryOrigin =')
end = source.index('                    RESULTS.addProperty("source",', start)
source = source[:start] + '''                    Path primary = net.minecraftforge.fml.ModList.get().getModFileById("renderscope").getFile().getFilePath();
                    if (Files.isRegularFile(primary)) {
                        var sha = java.security.MessageDigest.getInstance("SHA-256");
                        try (var stream = Files.newInputStream(primary)) {
                            byte[] buffer = new byte[65536];
                            for (int read; (read = stream.read(buffer)) != -1;) sha.update(buffer, 0, read);
                        }
                        RESULTS.addProperty("primaryJarSha256", java.util.HexFormat.of().formatHex(sha.digest()));
                    }
''' + source[end:]
source = source.replace('expectedOptionalMods.has("sodium") && !expectedOptionalMods.has("iris")',
    '(expectedOptionalMods.has("sodium") || expectedOptionalMods.has("rubidium") || expectedOptionalMods.has("embeddium")) && !expectedOptionalMods.has("iris")')
source = source.replace('actualSodiumNativeBatchesMeasured', 'actualFastNativeBatchesMeasured')
source = source.replace('optional Fabric mod', 'optional Forge mod').replace('Sodium native batches', 'fast native batches')
target = root / '.tools/generated-forge-smoke/src/dev/renderscope/smoke/InstalledSmokeTest.java'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(source, encoding='utf-8', newline='\n')
print(target.relative_to(root))
