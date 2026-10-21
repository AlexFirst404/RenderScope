package dev.renderscope.loadertest;

import java.nio.file.Path;
import net.neoforged.fml.ModList;

final class HarnessPlatform {
    private HarnessPlatform() { }
    static Path primaryJar() { return ModList.get().getModFileById("renderscope").getFile().getFilePath(); }
}
