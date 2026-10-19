package dev.renderscope.client;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.versions.forge.ForgeVersion;

import java.util.Locale;

/** Runtime identity: both 1.20.1 loaders use the forge mod ID and net.minecraftforge packages. */
public record LoaderInfo(String name, String version) {
    public static LoaderInfo current() {
        ModList mods = ModList.get();
        if (mods.getModContainerById("neoforge").isPresent()) {
            return new LoaderInfo("NeoForge", ForgeVersion.getVersion());
        }
        String displayName = mods.getModContainerById("forge")
                .map(container -> container.getModInfo().getDisplayName()).orElse("");
        String name = displayName.toLowerCase(Locale.ROOT).contains("neoforge") ? "NeoForge"
                : displayName.equalsIgnoreCase("Forge") ? "Forge" : "Unknown";
        return new LoaderInfo(name, ForgeVersion.getVersion());
    }
}
