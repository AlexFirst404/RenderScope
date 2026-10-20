package dev.renderscope.core;

import java.util.List;

public record ProfilerSnapshot(boolean enabled, double windowSeconds, Performance performance,
                               List<AggregateMetric> mods, List<AggregateMetric> renderers,
                               List<AggregateMetric> objects, List<InstanceMetric> instances,
                               List<FramePoint> timeline, CaptureStatus capture,
                               long droppedMeasurements, List<PhaseMetric> phases) {
    public ProfilerSnapshot {
        mods = List.copyOf(mods);
        renderers = List.copyOf(renderers);
        objects = List.copyOf(objects);
        instances = List.copyOf(instances);
        timeline = List.copyOf(timeline);
        phases = List.copyOf(phases);
    }
    public ProfilerSnapshot(boolean enabled, double windowSeconds, Performance performance,
                            List<AggregateMetric> mods, List<AggregateMetric> renderers,
                            List<AggregateMetric> objects, List<InstanceMetric> instances,
                            List<FramePoint> timeline, CaptureStatus capture, long droppedMeasurements) {
        this(enabled, windowSeconds, performance, mods, renderers, objects, instances,
            timeline, capture, droppedMeasurements, List.of());
    }
}
