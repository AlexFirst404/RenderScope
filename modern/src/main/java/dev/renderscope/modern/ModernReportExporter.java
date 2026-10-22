package dev.renderscope.modern;

import com.google.gson.*;
import dev.renderscope.core.CaptureReport;
import dev.renderscope.client.platform.PlatformServices;
import dev.renderscope.client.platform.PlatformAccess;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ModernReportExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private ModernReportExporter() { }
    public static Path write(CaptureReport capture, boolean positions) throws IOException {
        JsonObject root = ModernReportJson.toJson(capture, positions);
        root.addProperty("minecraftVersion", version("minecraft"));
        var platform = PlatformServices.current();
        root.addProperty("loader", platform.loader().name()); root.addProperty("loaderVersion", platform.loader().version());
        root.addProperty("renderscopeVersion", version("renderscope"));
        root.addProperty("threadCpuTimingEnabled", ModernHooks.SETTINGS.cpuTiming);
        JsonArray mods = new JsonArray();
        platform.mods().stream().sorted(java.util.Comparator.comparing(PlatformAccess.InstalledMod::id))
            .forEach(mod -> {
                JsonObject row = new JsonObject(); row.addProperty("id", mod.id());
                row.addProperty("name", mod.name()); row.addProperty("version", mod.version());
                mods.add(row);
            });
        root.add("installedMods", mods);
        Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("renderscope-reports");
        Files.createDirectories(directory);
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(ZoneId.systemDefault()).format(Instant.now());
        Path output = directory.resolve("renderscope-" + timestamp + ".json");
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) { GSON.toJson(root, writer); }
        return output;
    }
    private static String version(String modId) {
        return PlatformServices.current().mods().stream().filter(mod -> mod.id().equals(modId))
                .map(PlatformAccess.InstalledMod::version).findFirst().orElse("unknown");
    }
}
