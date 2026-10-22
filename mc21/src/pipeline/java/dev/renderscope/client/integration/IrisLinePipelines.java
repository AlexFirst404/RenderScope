package dev.renderscope.client.integration;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.renderscope.client.platform.PlatformServices;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/** Optional public Iris API binding for the exact pipeline ABI inspected on Minecraft 1.21.8. */
public final class IrisLinePipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope/Compatibility");
    private static int registeredPipelines;
    private static String failure = "";

    private IrisLinePipelines() { }

    public record State(boolean inspectedProfile, int registeredPipelines, String failure) { }

    private static boolean inspectedProfile() {
        if (!"1.21.8".equals(SharedConstants.VERSION_STRING)) return false;
        var mods = PlatformServices.current().mods();
        return mods.stream().anyMatch(mod -> mod.id().equals("minecraft") && mod.version().equals("1.21.8"))
                && mods.stream().anyMatch(mod -> mod.id().equals("iris") && mod.version().equals("1.9.6+mc1.21.8"));
    }

    /** Called once per private pipeline, before its first draw. No Iris implementation classes are linked. */
    public static synchronized void assign(RenderPipeline pipeline) {
        if (!inspectedProfile()) return;
        try {
            ClassLoader loader = IrisLinePipelines.class.getClassLoader();
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, loader);
            Class<?> programs = Class.forName("net.irisshaders.iris.api.v0.IrisProgram", false, loader);
            Object instance = api.getMethod("getInstance").invoke(null);
            Object lines = programs.getField("LINES").get(null);
            api.getMethod("assignPipeline", RenderPipeline.class, programs).invoke(instance, pipeline, lines);
            registeredPipelines++;
        } catch (ReflectiveOperationException | LinkageError error) {
            Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : error;
            if (failure.isEmpty()) LOGGER.warn("Iris line-pipeline API binding unavailable: {}", cause.getClass().getSimpleName());
            failure = cause.getClass().getSimpleName();
        }
    }

    public static synchronized State state() {
        return new State(inspectedProfile(), registeredPipelines, failure);
    }
}
