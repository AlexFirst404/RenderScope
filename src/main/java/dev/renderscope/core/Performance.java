package dev.renderscope.core;

public record Performance(int frames, double fps, double currentFrameMs, double averageFrameMs,
                          double onePercentLowFps, double p50FrameMs, double p95FrameMs,
                          double p99FrameMs, double worstFrameMs, double measuredRenderMs,
                          double entityRenderMs, double blockEntityRenderMs,
                          double rendererCallsPerFrame, double renderedEntitiesPerFrame,
                          double renderedBlockEntitiesPerFrame, int measuredObjects,
                          double verticesPerFrame, double estimatedBytesPerFrame) {}
