package dev.renderscope.client.report;

import com.google.gson.*;
import dev.renderscope.client.LoaderInfo;
import dev.renderscope.core.*;
import net.minecraft.SharedConstants;
import net.minecraftforge.fml.ModList;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Explicit allowlist serializer. Never serialize a raw game object or a raw instance key. */
public final class ReportExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private ReportExporter() { }

    public static Path write(Path gameDirectory, CaptureReport capture, boolean positions, String mode,
                             boolean cpuTiming) throws IOException {
        JsonObject root = toJson(capture, positions, mode, cpuTiming);
        root.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        LoaderInfo loader = LoaderInfo.current();
        root.addProperty("loader", loader.name());
        root.addProperty("loaderVersion", loader.version());
        root.addProperty("renderscopeVersion", ModList.get().getModContainerById("renderscope")
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown"));
        JsonArray mods = new JsonArray();
        for (var mod : ModList.get().getMods()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id",mod.getModId()); entry.addProperty("name",mod.getDisplayName());
            entry.addProperty("version",mod.getVersion().toString()); mods.add(entry);
        }
        root.add("installedMods", mods);
        Path directory = gameDirectory.resolve("renderscope-reports");
        Files.createDirectories(directory);
        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(ZoneId.systemDefault())
                .format(Instant.now());
        Path destination = directory.resolve("renderscope-"+stamp+".json");
        try (Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            GSON.toJson(root,writer);
        }
        return destination;
    }

    /** Public for privacy regression tests; positions are included only by this explicit opt-in. */
    public static JsonObject toJson(CaptureReport capture, boolean positions, String mode, boolean cpuTiming) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion",1);
        root.addProperty("recordedAt",Instant.ofEpochMilli(capture.startedAtEpochMillis()).toString());
        root.addProperty("durationSeconds",capture.durationSeconds());
        root.addProperty("frames",capture.frames());
        root.addProperty("measurementMode",mode);
        root.addProperty("threadCpuTimingEnabled",cpuTiming);
        root.addProperty("positionsIncluded",positions);
        root.addProperty("timingMeaning","Measured CPU-side renderer wall time; includes submission and driver waits, not GPU execution time.");
        root.addProperty("geometryMeaning","Observed BufferBuilder vertices and estimated vertex data; alternative rendering pipelines may bypass these counters. Not GPU uploads or VRAM.");
        root.addProperty("coverageMeaning","Measured standard entity/block entity renderer calls and attributed deferred Mekanism renderer calls. Flywheel instanced visual updates/draws and Create contraption virtual worlds are not covered; totals are not the complete cost of a mod.");
        root.addProperty("percentiles","Capture percentiles use bounded histograms; averages include frames with zero calls.");
        root.addProperty("droppedMeasurements",capture.droppedMeasurements());
        root.add("performance",GSON.toJsonTree(capture.performance()));
        root.add("mods",aggregates(capture.mods()));
        root.add("renderers",aggregates(capture.renderers()));
        root.add("objects",aggregates(capture.objects()));
        JsonArray instances = new JsonArray();
        for (InstanceMetric metric : capture.instances()) instances.add(instance(metric,positions));
        root.add("instances",instances);
        JsonArray timeline = new JsonArray();
        long start = capture.timeline().isEmpty() ? 0 : capture.timeline().get(0).timestampNanos();
        for (FramePoint point : capture.timeline()) {
            JsonObject row = new JsonObject();
            row.addProperty("secondsFromTimelineStart",(point.timestampNanos()-start)/1e9);
            row.addProperty("frameMs",point.frameMs());
            row.addProperty("measuredRendererMs",point.measuredRenderMs());
            row.addProperty("entityRendererMs",point.entityRenderMs());
            row.addProperty("blockEntityRendererMs",point.blockEntityRenderMs());
            row.addProperty("spike",point.spike());
            row.add("topModsMs",GSON.toJsonTree(point.topModMs()));
            if (point.worstInstance()!=null) row.add("worstInstance",instance(point.worstInstance(),positions));
            timeline.add(row);
        }
        root.add("timeline",timeline);
        return root;
    }

    private static JsonArray aggregates(List<AggregateMetric> metrics) {
        JsonArray result=new JsonArray();
        for (AggregateMetric metric:metrics) {
            JsonObject row=new JsonObject(); row.addProperty("id",metric.id());
            if (metric.kind()!=null) row.addProperty("kind",metric.kind().name());
            row.addProperty("registryOwner",metric.registryOwner());
            row.addProperty("rendererClass",metric.rendererClass());
            row.addProperty("rendererOwner",metric.rendererOwner());
            row.add("statistics",GSON.toJsonTree(metric.stats())); result.add(row);
        }
        return result;
    }

    private static JsonObject instance(InstanceMetric metric, boolean positions) {
        SampleContext context=metric.context();
        JsonObject row=new JsonObject();
        row.addProperty("registryId",context.registryId());
        row.addProperty("kind",context.key().kind().name());
        row.addProperty("registryOwner",context.registryOwner());
        row.addProperty("rendererClass",context.rendererClass());
        row.addProperty("rendererOwner",context.rendererOwner());
        row.add("statistics",GSON.toJsonTree(metric.stats()));
        if (positions) {
            row.addProperty("dimension",context.key().dimension());
            row.add("position",GSON.toJsonTree(context.position()));
        }
        return row;
    }
}
