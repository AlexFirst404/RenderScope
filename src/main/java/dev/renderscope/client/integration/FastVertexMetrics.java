package dev.renderscope.client.integration;

import dev.renderscope.client.RenderHooks;

/** Lifetime diagnostics count only successful fast writes inside an actual measured renderer. */
public final class FastVertexMetrics {
    private static long batches;
    private static long delegateVertices;
    private FastVertexMetrics() { }
    public static void batch(int count, int stride) {
        if (count <= 0 || stride <= 0 || !RenderHooks.collectingGeometry()) return;
        RenderHooks.vertices(count, stride);
        batches++;
    }
    public static void delegateVertex(int stride) {
        if (stride <= 0 || !RenderHooks.collectingGeometry()) return;
        RenderHooks.vertices(1, stride);
        delegateVertices++;
    }
    public static long observedBatchCalls() { return batches; }
    public static long observedDelegateVertices() { return delegateVertices; }
}
