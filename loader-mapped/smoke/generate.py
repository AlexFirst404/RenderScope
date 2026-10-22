"""Adapt the shared real-world QA assertions to named 1.21.9–1.21.11 loaders."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--minecraft', choices=('1.21.9', '1.21.10', '1.21.11'), required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()

source = (ROOT / 'modern/src/gametest/java/dev/renderscope/modern/smoke/ModernClientSmoke.java').read_text(encoding='utf-8')
source = '\n'.join(line for line in source.splitlines() if not line.startswith('import net.fabricmc.'))
source = source.replace('ModernSmokePlatform', 'NativeSmokePlatform')
source = source.replace('ModernClientSmoke implements FabricClientGameTest', 'NativeModernSmoke')
source = source.replace('@Override public void runTest(ClientGameTestContext context)', 'public void runTest(NativeSmokeContext context)')
source = source.replace('ClientGameTestContext context', 'NativeSmokeContext context')
source = source.replace('TestSingleplayerContext world', 'NativeSmokeContext.TestWorld world')
source = source.replace('FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString()', 'net.minecraft.SharedConstants.getCurrentVersion().id()')
source = source.replace('FabricLoader.getInstance().getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString()', 'dev.renderscope.client.LoaderInfo.current().version()')
source = source.replace('"fabric-development-client"', 'System.getProperty("renderscope.modern.smoke.environment", dev.renderscope.client.LoaderInfo.current().name().toLowerCase(Locale.ROOT)+"-development-client")')
source = source.replace('Path artifact = Path.of(System.getProperty("renderscope.modern.smoke.artifact"));', '''var activeLoader = dev.renderscope.client.LoaderInfo.current();
            results.addProperty("loader", activeLoader.name());
            require(activeLoader.name().equalsIgnoreCase(System.getProperty("renderscope.expectedLoader", activeLoader.name())), "Wrong runtime loader");
            require(activeLoader.version().equals(System.getProperty("renderscope.expectedLoaderVersion", activeLoader.version())), "Wrong runtime loader version");
            require(net.minecraft.SharedConstants.getCurrentVersion().id().equals(System.getProperty("renderscope.expectedMinecraft", net.minecraft.SharedConstants.getCurrentVersion().id())), "Wrong runtime Minecraft");
            results.addProperty("runtimeLoaderVerified", true);
            Path requested = Path.of(System.getProperty("renderscope.modern.smoke.artifact"));
            Path actualPrimary = NativeSmokeOrigin.primaryJar();
            boolean installed = results.get("environment").getAsString().equals("installed-production");
            require(!installed || Files.isRegularFile(actualPrimary), "Installed main mod is not loaded from a regular JAR");
            require(!installed || Files.isSameFile(actualPrimary, requested), "Loaded main JAR differs from requested artifact");
            Path artifact = installed ? actualPrimary : requested;''')
source = source.replace('results.addProperty("artifactSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(artifactBytes)));', '''results.addProperty("artifactSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(artifactBytes)));
            if (installed) {
                results.addProperty("primaryJarSha256", results.get("artifactSha256").getAsString());
                results.addProperty("primaryJarBytes", artifactBytes.length);
            }''')
if 'FabricLoader' in source or 'net.fabricmc' in source or 'ClientGameTestContext' in source:
    raise SystemExit('Shared harness contract changed; refusing an incomplete native adaptation')

output = args.output.resolve()
if not output.is_relative_to((ROOT / 'loader-mapped').resolve()):
    raise SystemExit('Generated QA sources must remain inside loader-mapped')
for old_name in ('NativeModernSmoke.java','NativeSmokeContext.java'):
    (output / 'dev/renderscope/modern' / old_name).unlink(missing_ok=True)
shared = ROOT / 'loader-mapped/smoke/java'
for template in shared.rglob('*.java'):
    context = template.read_text(encoding='utf-8')
    if args.minecraft == '1.21.11':
        context = context.replace('net.minecraft.world.level.GameRules(', 'net.minecraft.world.level.gamerules.GameRules(')
    destination = output / template.relative_to(shared)
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(context,encoding='utf-8')
package = output / 'dev/renderscope/modern/smoke'
package.mkdir(parents=True, exist_ok=True)
(package / 'NativeModernSmoke.java').write_text(source + '\n', encoding='utf-8')
print(f'Generated isolated native {args.minecraft} world/UI/capture harness.')
