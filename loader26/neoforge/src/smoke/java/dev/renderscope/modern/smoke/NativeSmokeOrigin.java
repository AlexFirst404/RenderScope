package dev.renderscope.modern.smoke;

final class NativeSmokeOrigin {
    static String startupIssues() { return net.neoforged.fml.ModLoader.getLoadingIssues().toString(); }
    static java.nio.file.Path mainArtifact() {
        return net.neoforged.fml.ModList.get().getModFileById("renderscope").getFile().getFilePath();
    }
}
