package dev.renderscope.modern.smoke;

final class NativeSmokeOrigin {
    static String startupIssues() {
        return net.minecraftforge.fml.ModLoader.getWarnings().stream()
            .map(net.minecraftforge.fml.ModLoadingWarning::formatToString).collect(java.util.stream.Collectors.joining("; "));
    }
    static java.nio.file.Path mainArtifact() {
        return net.minecraftforge.fml.ModList.getModFileById("renderscope").getFile().getFilePath();
    }
}
