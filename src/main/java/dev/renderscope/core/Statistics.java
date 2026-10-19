package dev.renderscope.core;

import java.util.Set;

/** CPU wall-clock renderer times; geometry fields are estimates, not GPU measurements. */
public record Statistics(double averageMs, double p95Ms, double peakMs, double cpuMs,
                         double callsPerFrame, long calls, long failures,
                         double verticesPerFrame, double estimatedBytesPerFrame,
                         int instances, Set<String> renderTypes) {
    public Statistics { renderTypes = renderTypes == null ? Set.of() : Set.copyOf(renderTypes); }
}
