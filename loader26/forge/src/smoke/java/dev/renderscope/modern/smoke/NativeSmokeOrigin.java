package dev.renderscope.modern.smoke;

final class NativeSmokeOrigin {
    static java.nio.file.Path mainArtifact() {
        return net.minecraftforge.fml.ModList.getModFileById("renderscope").getFile().getFilePath();
    }
}
