package dev.renderscope.client.integration;

import dev.renderscope.client.RenderHooks;

/** Real shader-mod buffer requests inside a currently measured object invocation. */
public final class ShaderBufferMetrics {
    private static long observedRequests;
    private ShaderBufferMetrics() { }

    public static void requested(String renderType) {
        if (!RenderHooks.collectingGeometry()) return;
        RenderHooks.renderType(renderType);
        observedRequests++;
    }

    public static long observedRequests() { return observedRequests; }
}
