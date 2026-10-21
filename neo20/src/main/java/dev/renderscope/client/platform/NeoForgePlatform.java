package dev.renderscope.client.platform;

import dev.renderscope.client.LoaderInfo;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NeoForgePlatform implements PlatformAccess {
    @Override public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
    @Override public LoaderInfo loader() { return new LoaderInfo("NeoForge", NeoForgeVersion.getVersion()); }
    @Override public List<InstalledMod> mods() {
        return ModList.get().getMods().stream().map(mod -> new InstalledMod(mod.getModId(), mod.getDisplayName(),
                mod.getVersion().toString())).toList();
    }
    @Override public Map<String, String> packageOwners() {
        Map<String, String> result = new HashMap<>();
        for (var file : FMLLoader.getLoadingModList().getModFiles()) {
            if (file.getMods().isEmpty()) continue;
            String id = file.getMods().get(0).getModId();
            for (String pkg : file.getFile().getSecureJar().getPackages()) result.putIfAbsent(pkg, id);
        }
        return Map.copyOf(result);
    }
}
