package dev.renderscope.fabric;

import dev.renderscope.client.LoaderInfo;
import dev.renderscope.client.platform.PlatformAccess;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

public final class FabricPlatform implements PlatformAccess {
    @Override public Path configDirectory() { return FabricLoader.getInstance().getConfigDir(); }
    @Override public LoaderInfo loader() {
        return new LoaderInfo("Fabric", FabricLoader.getInstance().getModContainer("fabricloader")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
    }
    @Override public List<InstalledMod> mods() {
        return FabricLoader.getInstance().getAllMods().stream().map(mod -> new InstalledMod(mod.getMetadata().getId(),
                mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString())).toList();
    }
    @Override public Map<String, String> packageOwners() {
        Map<String, String> owners = new HashMap<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            String owner = mod.getMetadata().getId();
            if (owner.equals("minecraft") || owner.equals("java")) continue;
            for (Path root : mod.getRootPaths()) {
                try (var paths = Files.walk(root)) {
                    paths.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                        Path parent = root.relativize(path).getParent();
                        if (parent != null) owners.putIfAbsent(parent.toString().replace('\\', '.').replace('/', '.'), owner);
                    });
                } catch (IOException | UncheckedIOException failure) {
                    LoggerFactory.getLogger("RenderScope").warn("Cannot index renderer packages for {}", owner, failure);
                }
            }
        }
        return Map.copyOf(owners);
    }
}
