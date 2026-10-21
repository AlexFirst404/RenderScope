package dev.renderscope.client.platform;

import dev.renderscope.client.LoaderInfo;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FML 10+ exposes the actual Java module descriptor instead of the former SecureJar API. */
public final class NeoForgePlatform implements PlatformAccess {
    @Override public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
    @Override public LoaderInfo loader() {
        String version = ModList.get().getModContainerById("neoforge")
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
        return new LoaderInfo("NeoForge", version);
    }
    @Override public List<InstalledMod> mods() {
        return ModList.get().getMods().stream().map(mod -> new InstalledMod(mod.getModId(), mod.getDisplayName(),
                mod.getVersion().toString())).toList();
    }
    @Override public Map<String, String> packageOwners() {
        Map<String, String> owners = new HashMap<>();
        for (var file : ModList.get().getModFiles()) {
            if (file.getMods().isEmpty()) continue;
            String id = file.getMods().getFirst().getModId();
            ModFile implementation = (ModFile) file.getFile();
            for (String pkg : implementation.getModuleDescriptor().packages()) owners.putIfAbsent(pkg, id);
        }
        return Map.copyOf(owners);
    }
}
