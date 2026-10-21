package dev.renderscope.modern.smoke;

final class NativeSmokeOrigin {
    static java.nio.file.Path mainArtifact() {
        return net.neoforged.fml.ModList.get().getModFileById("renderscope").getFile().getFilePath();
    }
}
