package dev.renderscope.core;

import java.util.List;

/** A completed capture. Exporters must omit instance/location fields unless explicitly enabled. */
public record CaptureReport(long startedAtEpochMillis, double durationSeconds, int frames,
                            Performance performance, List<AggregateMetric> mods,
                            List<AggregateMetric> renderers, List<AggregateMetric> objects,
                            List<InstanceMetric> instances, List<FramePoint> timeline,
                            long droppedMeasurements) {
    public CaptureReport {
        mods = List.copyOf(mods);
        renderers = List.copyOf(renderers);
        objects = List.copyOf(objects);
        instances = List.copyOf(instances);
        timeline = List.copyOf(timeline);
    }
}
