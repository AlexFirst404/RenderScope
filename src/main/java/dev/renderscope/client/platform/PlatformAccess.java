package dev.renderscope.client.platform;

import dev.renderscope.client.LoaderInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Loader metadata only. The profiler and Minecraft UI do not depend on a loader's event bus. */
public interface PlatformAccess {
    Path configDirectory();
    LoaderInfo loader();
    List<InstalledMod> mods();
    Map<String, String> packageOwners();
    default boolean hasMod(String id) { return mods().stream().anyMatch(mod -> mod.id().equals(id)); }
    default String displayName(String id) {
        return mods().stream().filter(mod -> mod.id().equals(id)).map(InstalledMod::name).findFirst().orElse(id);
    }
    record InstalledMod(String id, String name, String version) { }
}
