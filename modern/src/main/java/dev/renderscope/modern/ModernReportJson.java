package dev.renderscope.modern;

import com.google.gson.*;
import dev.renderscope.core.*;
import dev.renderscope.client.integration.ShaderPasses;

import java.time.Instant;
import java.util.List;

/** Explicit allowlist. An instance key, UUID, world object or absolute path is never serialized. */
public final class ModernReportJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private ModernReportJson() { }
    public static JsonObject toJson(CaptureReport capture, boolean positions) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("recordedAt", Instant.ofEpochMilli(capture.startedAtEpochMillis()).toString());
        root.addProperty("durationSeconds", capture.durationSeconds());
        root.addProperty("frames", capture.frames());
        root.addProperty("measurementMode", "CPU_TIMING_ONLY");
        root.addProperty("positionsIncluded", positions);
        root.addProperty("timingMeaning", "Exclusive CPU-side render-state extraction and submission intervals, with separate sources. Calls count both stages. Deferred feature execution and GPU work are outside these per-object intervals.");
        root.addProperty("coverageMeaning", "Real-world entities and block entities reached through vanilla extraction/submission dispatchers. Registry and renderer implementation ownership are separate. Preview entities and virtual block worlds are excluded. Shader shadow calls are excluded when the optional public Iris API identifies them; shaderCoverage records API availability/failures. Terrain, particles, items and GUI are not attributed.");
        root.add("shaderCoverage", GSON.toJsonTree(ShaderPasses.state()));
        root.addProperty("geometryAvailable", false);
        root.addProperty("geometryMeaning", "Unavailable: later deferred feature batches are not assigned to objects. Zero geometry fields do not assert that an object generated no geometry.");
        root.addProperty("gpuTimingAvailable", false);
        root.addProperty("gpuMeaning", "Unavailable in this render-state adapter. CPU intervals are not GPU timings, VRAM or predicted FPS gains.");
        root.addProperty("percentiles", "Capture histograms are bounded; averages include frames with zero calls.");
        root.addProperty("droppedMeasurements", capture.droppedMeasurements());
        root.add("performance", GSON.toJsonTree(capture.performance()));
        root.add("mods", aggregates(capture.mods()));
        root.add("renderers", aggregates(capture.renderers()));
        root.add("objects", aggregates(capture.objects()));
        JsonArray instances = new JsonArray();
        for (InstanceMetric metric : capture.instances()) instances.add(instance(metric, positions));
        root.add("instances", instances);
        JsonArray phases = new JsonArray();
        for (PhaseMetric phase : capture.phases()) {
            JsonObject row = new JsonObject(); row.addProperty("id", phase.id());
            row.add("statistics", GSON.toJsonTree(phase.stats())); phases.add(row);
        }
        root.add("phases", phases);
        JsonArray timeline = new JsonArray();
        long first = capture.timeline().isEmpty() ? 0 : capture.timeline().get(0).timestampNanos();
        for (FramePoint point : capture.timeline()) {
            JsonObject row = new JsonObject();
            row.addProperty("secondsFromTimelineStart", (point.timestampNanos() - first) / 1e9);
            row.addProperty("frameMs", point.frameMs()); row.addProperty("measuredCpuMs", point.measuredRenderMs());
            row.addProperty("entityCpuMs", point.entityRenderMs()); row.addProperty("blockEntityCpuMs", point.blockEntityRenderMs());
            row.addProperty("spike", point.spike()); row.add("topModsMs", GSON.toJsonTree(point.topModMs()));
            if (point.worstInstance() != null) row.add("worstInstance", instance(point.worstInstance(), positions));
            timeline.add(row);
        }
        root.add("timeline", timeline);
        return root;
    }
    private static JsonArray aggregates(List<AggregateMetric> metrics) {
        JsonArray rows = new JsonArray();
        for (AggregateMetric metric : metrics) {
            JsonObject row = new JsonObject(); row.addProperty("id", metric.id());
            if (metric.kind() != null) row.addProperty("kind", metric.kind().name());
            row.addProperty("registryOwner", metric.registryOwner()); row.addProperty("rendererClass", metric.rendererClass());
            row.addProperty("rendererOwner", metric.rendererOwner()); row.add("statistics", GSON.toJsonTree(metric.stats()));
            rows.add(row);
        }
        return rows;
    }
    private static JsonObject instance(InstanceMetric metric, boolean positions) {
        SampleContext context = metric.context();
        JsonObject row = new JsonObject(); row.addProperty("registryId", context.registryId());
        row.addProperty("kind", context.key().kind().name()); row.addProperty("registryOwner", context.registryOwner());
        row.addProperty("rendererClass", context.rendererClass()); row.addProperty("rendererOwner", context.rendererOwner());
        row.add("statistics", GSON.toJsonTree(metric.stats()));
        if (positions) {
            row.addProperty("dimension", context.key().dimension());
            row.add("position", GSON.toJsonTree(context.position()));
        }
        return row;
    }
}
