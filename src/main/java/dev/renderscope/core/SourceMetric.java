package dev.renderscope.core;

/** Sum of exclusive observed task intervals, divided by all frames in the window/capture. */
public record SourceMetric(double averageMs, double cpuMs, double callsPerFrame,
                           long calls, long failures) { }
