package dev.renderscope.client.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Public Iris/Oculus API only; no shader implementation fields or per-object GPU estimates. */
public final class ShaderPasses {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope/Compatibility");
    private static volatile Probe probe;
    private static volatile String failure;
    private static volatile boolean observedShadowPass;
    private ShaderPasses() { }

    private record Probe(MethodHandle shadows, MethodHandle enabled, String apiClass) { }
    public record State(boolean shaderApiPresent, boolean shadowDetectionAvailable, boolean shaderPackEnabled,
                        boolean shadowPassActive, String apiClass, String failure) { }

    private static Probe probe() {
        Probe value = probe;
        if (value != null) return value;
        synchronized (ShaderPasses.class) {
            if (probe != null) return probe;
            for (String name : new String[]{"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
                try {
                    Class<?> api = Class.forName(name, false, ShaderPasses.class.getClassLoader());
                    Object instance = api.getMethod("getInstance").invoke(null);
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    MethodHandle shadow = lookup.unreflect(api.getMethod("isRenderingShadowPass")).bindTo(instance).asType(MethodType.methodType(boolean.class));
                    MethodHandle enabled = lookup.unreflect(api.getMethod("isShaderPackInUse")).bindTo(instance).asType(MethodType.methodType(boolean.class));
                    return probe = new Probe(shadow, enabled, name);
                } catch (ClassNotFoundException ignored) {
                    // Both namespace generations are optional.
                } catch (ReflectiveOperationException | LinkageError error) {
                    failure = error.getClass().getSimpleName();
                    LOGGER.warn("Shader API is present but shadow detection is unavailable: {}", failure);
                    return probe = new Probe(null, null, name);
                }
            }
            return probe = new Probe(null, null, "");
        }
    }

    public static boolean isShadowPass() {
        Probe value = probe();
        if (value.shadows() == null || failure != null) return false;
        try {
            boolean shadow = (boolean) value.shadows().invokeExact();
            if (shadow && !observedShadowPass) observedShadowPass = true;
            return shadow;
        }
        catch (Throwable error) { failed(error); return false; }
    }

    /** Lifetime diagnostic: a real shader API query returned true, rather than assuming pack use. */
    public static boolean observedShadowPass() { return observedShadowPass; }

    public static State state() {
        Probe value = probe();
        boolean active = false;
        if (value.enabled() != null && failure == null) {
            try { active = (boolean) value.enabled().invokeExact(); }
            catch (Throwable error) { failed(error); }
        }
        return new State(!value.apiClass().isEmpty(), value.shadows() != null && failure == null,
                active, isShadowPass(), value.apiClass(), failure == null ? "" : failure);
    }

    private static void failed(Throwable error) {
        if (failure == null) {
            failure = error.getClass().getSimpleName();
            LOGGER.warn("Shader API query failed; coverage is partial: {}", failure);
        }
    }
}
