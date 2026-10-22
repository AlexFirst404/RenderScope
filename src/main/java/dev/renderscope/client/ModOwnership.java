package dev.renderscope.client;

import dev.renderscope.client.platform.PlatformServices;
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
        packageOwners.putAll(PlatformServices.current().packageOwners());
    }

    public String rendererOwner(Class<?> renderer) { return classOwners.get(renderer); }

    public static String displayName(String namespace) {
        if (namespace == null || namespace.equals("unknown")) return "Unknown";
        if (namespace.equals("minecraft")) return "Minecraft";
        return PlatformServices.current().displayName(namespace);
    }
}
