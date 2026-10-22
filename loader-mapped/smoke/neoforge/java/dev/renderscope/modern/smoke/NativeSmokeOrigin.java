package dev.renderscope.modern.smoke;

import java.nio.file.Path;
import net.neoforged.fml.ModList;

/** Actual loader-discovered origin; installed QA never infers this from an input property. */
final class NativeSmokeOrigin {
    private NativeSmokeOrigin() { }
    static Path primaryJar() { return ModList.get().getModFileById("renderscope").getFile().getFilePath(); }
}
