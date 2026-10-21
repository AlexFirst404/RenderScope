package dev.renderscope.client.platform;

import dev.renderscope.client.LoaderInfo;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.versions.forge.ForgeVersion;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 1.20.1 Forge and NeoForge 47 share the Forge API namespace. */
public final class ForgePlatform implements PlatformAccess {
    @Override public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
    @Override public LoaderInfo loader() {
        ModList mods = ModList.get();
        String display = mods.getModContainerById("forge").map(mod -> mod.getModInfo().getDisplayName()).orElse("");
        boolean neo = mods.getModContainerById("neoforge").isPresent() || display.toLowerCase(Locale.ROOT).contains("neoforge");
        return new LoaderInfo(neo ? "NeoForge" : "Forge", ForgeVersion.getVersion());
    }
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
