package dev.renderscope.client.platform;

import dev.renderscope.client.LoaderInfo;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.versions.forge.ForgeVersion;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Modern Forge exposes the mod list through static accessors. */
public final class ForgePlatform implements PlatformAccess {
    @Override public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
    @Override public LoaderInfo loader() { return new LoaderInfo("Forge", ForgeVersion.getVersion()); }
    @Override public List<InstalledMod> mods() {
        return ModList.getMods().stream().map(mod -> new InstalledMod(mod.getModId(), mod.getDisplayName(),
                mod.getVersion().toString())).toList();
    }
    @Override public Map<String, String> packageOwners() {
        Map<String, String> owners = new HashMap<>();
        for (var file : ModList.getModFiles()) {
            if (file.getMods().isEmpty()) continue;
            String id = file.getMods().getFirst().getModId();
            for (String pkg : file.getFile().getSecureJar().moduleDataProvider().descriptor().packages()) owners.putIfAbsent(pkg, id);
        }
        return Map.copyOf(owners);
    }
}
