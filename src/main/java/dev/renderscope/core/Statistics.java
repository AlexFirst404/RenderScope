package dev.renderscope.core;

import java.util.Set;
import java.util.Map;

/** CPU wall-clock renderer times; geometry fields are estimates, not GPU measurements. */
public record Statistics(double averageMs, double p95Ms, double peakMs, double cpuMs,
                         double callsPerFrame, long calls, long failures,
                         double verticesPerFrame, double estimatedBytesPerFrame,
                         int instances, Set<String> renderTypes,
                         Map<MeasurementSource, SourceMetric> sources) {
    public Statistics {
        renderTypes = renderTypes == null ? Set.of() : Set.copyOf(renderTypes);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }
    public Statistics(double averageMs, double p95Ms, double peakMs, double cpuMs,
                      double callsPerFrame, long calls, long failures, double verticesPerFrame,
                      double estimatedBytesPerFrame, int instances, Set<String> renderTypes) {
        this(averageMs, p95Ms, peakMs, cpuMs, callsPerFrame, calls, failures,
            verticesPerFrame, estimatedBytesPerFrame, instances, renderTypes, Map.of());
    }
}
