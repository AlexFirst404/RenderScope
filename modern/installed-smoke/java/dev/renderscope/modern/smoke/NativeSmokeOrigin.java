package dev.renderscope.modern.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Actual loader origin, rather than the requested path or a profiler class location. */
final class NativeSmokeOrigin {
    static Path primaryJar() {
        var paths = FabricLoader.getInstance().getModContainer("renderscope").orElseThrow().getOrigin().getPaths();
        if (paths.size() != 1 || !Files.isRegularFile(paths.getFirst()))
            throw new IllegalStateException("Installed primary must have exactly one regular JAR origin");
        return paths.getFirst().toAbsolutePath().normalize();
    }
    static String startupIssues() {
        return "Actual Fabric Loader " + FabricLoader.getInstance().getModContainer("fabricloader").orElseThrow()
                .getMetadata().getVersion().getFriendlyString() + "; startup error screen remains visible; inspect the client console";
    }
}
