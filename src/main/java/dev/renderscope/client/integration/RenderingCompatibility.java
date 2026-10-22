package dev.renderscope.client.integration;

import java.util.ArrayList;
import java.util.List;

/** Value-only coverage information for UI/reports; detected does not mean runtime-tested. */
public final class RenderingCompatibility {
    private RenderingCompatibility() { }
    public record Integration(String id, String version, boolean inspectedAdapter, String coverage) { }
    public record Snapshot(List<Integration> integrations, ShaderPasses.State shaders,
                           long observedFastBatchCalls, long observedFastDelegateVertices,
                           long observedShaderBufferRequests, List<String> limitations) {
        public Snapshot { integrations = List.copyOf(integrations); limitations = List.copyOf(limitations); }
    }

    public static Snapshot snapshot() {
        var versions = IntegrationVersions.installed();
        List<Integration> installed = new ArrayList<>();
        List<String> limits = new ArrayList<>();
        // Embeddium supplies a compatibility mod ID called rubidium. It is one backend, not a conflict.
        String backend = versions.containsKey("embeddium") ? "embeddium" : versions.containsKey("rubidium") ? "rubidium"
                : versions.containsKey("sodium") ? "sodium" : null;
        if (backend != null) {
            boolean inspected = IntegrationVersions.supportsFastVertices(versions);
            installed.add(new Integration(backend, versions.get(backend), inspected, inspected
                    ? "Immediate-mode native vertex-copy adapter enabled; counters show observed measured calls"
                    : "Ordinary renderer timing remains active; native vertex-copy ABI has not been inspected"));
            limits.add("Terrain/chunk batches are not attributed to individual mods or world objects.");
            if (!inspected) limits.add("Vertex estimates may omit this renderer's native fast paths; use Timing Only when required.");
        }
        if (versions.containsKey("flywheel")) {
            boolean supported = IntegrationVersions.supportsFlywheel(versions);
            installed.add(new Integration("flywheel", versions.get("flywheel"), supported, supported
                    ? "Visual CPU tasks and separate shared batch CPU/GPU phase; per-object instanced geometry unavailable"
                    : "Optional visual adapter disabled for this version; ordinary dispatcher timing has partial coverage"));
        }
        if (versions.containsKey("mekanism_extras")) installed.add(new Integration("mekanism_extras", versions.get("mekanism_extras"),
                IntegrationVersions.supportsMekanismDeferred(versions), "Registry and renderer owners remain separate; native Mekanism deferred callbacks reuse the base integration"));
        ShaderPasses.State shaders = ShaderPasses.state();
        if (shaders.shaderApiPresent()) {
            String shader = versions.containsKey("oculus") ? "oculus" : "iris";
            installed.add(new Integration(shader, versions.getOrDefault(shader, "unknown"), shaders.shadowDetectionAvailable(),
                    shaders.shadowDetectionAvailable() ? "Shadow pass excluded from ordinary object and shared Flywheel measurements; "
                            + (IntegrationVersions.supportsShaderBuffers(versions) ? "shader buffer RenderType requests observed separately"
                            : "shader buffer override ABI has not been inspected")
                            : "Shadow API unavailable; renderer attribution is partial"));
            limits.add("Shader execution and terrain GPU cost are not distributed among mods or individual objects.");
        }
        return new Snapshot(installed, shaders, FastVertexMetrics.observedBatchCalls(), FastVertexMetrics.observedDelegateVertices(),
                ShaderBufferMetrics.observedRequests(), limits);
    }
}
