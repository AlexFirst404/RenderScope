package dev.renderscope.client;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import java.util.HashMap;
import java.util.Map;

/** Registry ownership and renderer implementation ownership deliberately stay separate. */
public final class ModOwnership {
    private final Map<String, String> packageOwners = new HashMap<>();
    private final ClassValue<String> classOwners = new ClassValue<>() {
        @Override protected String computeValue(Class<?> type) {
            String name = type.getName();
            if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) return "minecraft";
            return packageOwners.getOrDefault(type.getPackageName(), "unknown");
        }
    };

    public ModOwnership() {
        for (var file : FMLLoader.getLoadingModList().getModFiles()) {
            if (file.getMods().isEmpty()) continue;
            String owner = file.getMods().get(0).getModId();
            for (String pkg : file.getFile().getSecureJar().getPackages()) packageOwners.putIfAbsent(pkg, owner);
        }
    }

    public String rendererOwner(Class<?> renderer) { return classOwners.get(renderer); }

    public static String displayName(String namespace) {
        if (namespace == null || namespace.equals("unknown")) return "Unknown";
        if (namespace.equals("minecraft")) return "Minecraft";
        return ModList.get().getModContainerById(namespace)
                .map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace);
    }
}
