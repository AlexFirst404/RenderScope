package dev.renderscope.client.integration;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Early-loader metadata without linking either loader or loading optional renderer classes. */
public final class IntegrationVersions {
    private static volatile Map<String, String> cached;
    private IntegrationVersions() { }

    public static Map<String, String> installed() {
        Map<String, String> value = cached;
        if (value != null) return value;
        synchronized (IntegrationVersions.class) {
            if (cached != null) return cached;
            Map<String, String> found = new HashMap<>();
            readFabric(found);
            if (found.isEmpty()) readForge(found);
            value = Map.copyOf(found);
            // Some mixin services ask before loader discovery. Never cache an incomplete empty list.
            if (!value.isEmpty()) cached = value;
            return value;
        }
    }

    private static void readFabric(Map<String, String> found) {
        try {
            ClassLoader loader = IntegrationVersions.class.getClassLoader();
            Class<?> api = Class.forName("net.fabricmc.loader.api.FabricLoader", false, loader);
            Object instance = api.getMethod("getInstance").invoke(null);
            Method metadata = Class.forName("net.fabricmc.loader.api.ModContainer", false, loader).getMethod("getMetadata");
            Class<?> info = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata", false, loader);
            Method versionString = Class.forName("net.fabricmc.loader.api.Version", false, loader).getMethod("getFriendlyString");
            for (Object container : (Iterable<?>) api.getMethod("getAllMods").invoke(instance)) {
                Object mod = metadata.invoke(container);
                found.put((String) info.getMethod("getId").invoke(mod), (String) versionString.invoke(info.getMethod("getVersion").invoke(mod)));
            }
        } catch (ReflectiveOperationException | LinkageError ignored) { }
    }

    private static void readForge(Map<String, String> found) {
        try {
            Class<?> api = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, IntegrationVersions.class.getClassLoader());
            Object list = api.getMethod("getLoadingModList").invoke(null);
            if (list == null) return;
            for (Object info : (Iterable<?>) list.getClass().getMethod("getMods").invoke(list)) {
                found.put((String) info.getClass().getMethod("getModId").invoke(info), info.getClass().getMethod("getVersion").invoke(info).toString());
            }
        } catch (ReflectiveOperationException | LinkageError ignored) { }
    }

    /** Exact inspected ABI families; unknown versions retain ordinary renderer timing. */
    public static boolean supportsFastVertices(Map<String, String> versions) {
        if (!"1.20.1".equals(versions.get("minecraft"))) return false;
        if (versions.containsKey("embeddium")) return "0.3.31+mc1.20.1".equals(versions.get("embeddium"));
        // Rubidium also publishes Sodium 0.5.2 as a compatibility ID.
        if (versions.containsKey("rubidium")) return "0.7.1".equals(versions.get("rubidium"));
        if (versions.containsKey("sodium")) return Set.of("0.5.13+mc1.20.1", "0.5.12-beta.2+mc1.20.1").contains(versions.get("sodium"));
        return false;
    }

    public static boolean supportsFlywheel(Map<String, String> versions) { return "1.20.1".equals(versions.get("minecraft")) && "1.0.5".equals(versions.get("flywheel")); }
    public static boolean supportsShaderBuffers(Map<String, String> versions) {
        if (!"1.20.1".equals(versions.get("minecraft"))) return false;
        if (versions.containsKey("oculus")) return "1.8.0".equals(versions.get("oculus"));
        return "1.7.6+mc1.20.1".equals(versions.get("iris"));
    }
    // Build .80 deliberately publishes 10.4.16 in mods.toml, not the filename's full version.
    public static boolean supportsMekanismDeferred(Map<String, String> versions) { return "1.20.1".equals(versions.get("minecraft")) && Set.of("10.4.16", "10.4.16.80").contains(versions.getOrDefault("mekanism", "")); }
}
