package dev.renderscope.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual values from a frame, including attribution retained when that frame was a spike. */
public record FramePoint(long timestampNanos, double frameMs, double measuredRenderMs,
                         double entityRenderMs, double blockEntityRenderMs, boolean spike,
                         Map<String, Double> topModMs, InstanceMetric worstInstance) {
    public FramePoint { topModMs = Collections.unmodifiableMap(new LinkedHashMap<>(topModMs)); }
}
