package dev.renderscope.client;

import dev.renderscope.core.*;
import dev.renderscope.client.integration.FlywheelHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import java.util.HashSet;
import java.util.Set;

/** Render thread scope. Consumers and renderer arguments are never wrapped or retained. */
public final class RenderHooks {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final DeferredContexts DEFERRED = new DeferredContexts(4096);
    private static volatile boolean geometryEnabled;
    private static boolean worldPass;
    private static volatile boolean frameOpen;
    private static long frameStarted;
    private static Object worldSession;
    private static String dimensionSession;
    private static long measuredDeferredCalls;
    private static long queuedDeferredCalls;
    private static long renderedDeferredCalls;

    private RenderHooks() { }
    public static void setGeometryEnabled(boolean enabled) { geometryEnabled = enabled; }
    public static void worldPass(boolean active) { worldPass = active; }
    public static boolean isWorldPass() { return frameOpen && worldPass; }

    public static void beginFrame() {
        ACTIVE.remove();
        DEFERRED.clear();
        var runtime = ClientRuntime.current();
        if (runtime == null) return;
        var level = Minecraft.getInstance().level;
        String dimension = level == null ? "" : level.dimension().location().toString();
        if (worldSession != level || !dimension.equals(dimensionSession)) {
            runtime.profiler().clear();
            runtime.overlay().clear();
            worldSession = level;
            dimensionSession = dimension;
            measuredDeferredCalls = 0;
            queuedDeferredCalls = 0;
            renderedDeferredCalls = 0;
            FlywheelHooks.clear();
            FlywheelGpuTimers.reset();
        }
        worldPass = false;
        frameOpen = level != null;
        if (frameOpen) {
            frameStarted = System.nanoTime();
            runtime.profiler().beginFrame();
            FlywheelHooks.refreshEntityContexts();
        }
    }

    public static void endFrame() {
        if (frameOpen) {
            frameOpen = false;
            ClientRuntime.current().profiler().endFrame(System.nanoTime() - frameStarted);
        }
        worldPass = false;
        ACTIVE.remove();
        DEFERRED.clear();
    }

    public static Scope begin(Entity entity, Object renderer) {
        var runtime = ClientRuntime.current();
        if (runtime == null || !frameOpen || !worldPass || !runtime.settings().profiling) return null;
        SampleContext context = context(entity, renderer);
        return context == null ? null : begin(runtime, context);
    }

    /** Value-only metadata for optional visual creation, also while profiling is disabled. */
    public static SampleContext context(Entity entity, Object renderer) {
        var runtime = ClientRuntime.current();
        if (runtime == null || entity.level() != Minecraft.getInstance().level) return null;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        AABB box = entity.getBoundingBox();
        return new SampleContext(new InstanceKey(ObjectKind.ENTITY,
                entity.level().dimension().location().toString(), Integer.toString(entity.getId())),
                id.toString(), id.getNamespace(), renderer.getClass().getName(),
                runtime.ownership().rendererOwner(renderer.getClass()),
                new Position(entity.getX(), entity.getY(), entity.getZ()), bounds(box));
    }

    public static Scope begin(BlockEntity entity, Object renderer) {
        var runtime = ClientRuntime.current();
        if (runtime == null || !frameOpen || !worldPass || !runtime.settings().profiling) return null;
        SampleContext context = context(entity, renderer);
        return context == null ? null : begin(runtime, context);
    }

    public static SampleContext context(BlockEntity entity, Object renderer) {
        var runtime = ClientRuntime.current();
        if (runtime == null || entity.getLevel() == null || entity.getLevel() != Minecraft.getInstance().level) return null;
        var pos = entity.getBlockPos();
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
        if (id == null) return null;
        return new SampleContext(new InstanceKey(ObjectKind.BLOCK_ENTITY,
                entity.getLevel().dimension().location().toString(), pos.getX() + "," + pos.getY() + "," + pos.getZ()),
                id.toString(), id.getNamespace(), renderer.getClass().getName(),
                runtime.ownership().rendererOwner(renderer.getClass()),
                new Position(pos.getX(), pos.getY(), pos.getZ()),
                new Bounds(pos.getX(), pos.getY(), pos.getZ(), pos.getX()+1, pos.getY()+1, pos.getZ()+1));
    }

    private static Bounds bounds(AABB b) { return new Bounds(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ); }
    private static Scope begin(ClientRuntime runtime, SampleContext context) {
        Scope scope = new Scope(runtime.profiler(), ACTIVE.get(), geometryEnabled, context);
        scope.token = runtime.profiler().beginCall(context);
        if (scope.token == null) return null;
        ACTIVE.set(scope);
        return scope;
    }

    /** CPU visual work can run on Flywheel workers, outside a vanilla renderer/world pass. */
    public static Scope beginVisual(SampleContext context, MeasurementSource source, RenderProfiler.FrameHandle frame) {
        var runtime = ClientRuntime.current();
        if (runtime == null || context == null || frame == null) return null;
        Scope scope = new Scope(runtime.profiler(), ACTIVE.get(), false, context);
        scope.token = runtime.profiler().beginCall(context, source, frame);
        if (scope.token == null) return null;
        ACTIVE.set(scope);
        return scope;
    }

    /** Completion callbacks may synchronously start a different plan: exclude that interval. */
    public static void withoutScope(Runnable continuation) {
        Scope previous = ACTIVE.get();
        if (previous == null) { continuation.run(); return; }
        RenderProfiler.ExcludedInterval interval = previous.profiler.beginExcludedInterval(previous.token);
        ACTIVE.remove();
        try { continuation.run(); }
        finally {
            previous.profiler.endExcludedInterval(interval);
            ACTIVE.set(previous);
        }
    }

    /** Optional integrations call this while a measured renderer queues deferred world work. */
    public static void rememberDeferred(Object task, String renderType) {
        if (!frameOpen || !worldPass) return;
        queuedDeferredCalls++;
        Scope scope = ACTIVE.get();
        if (scope == null) return;
        if (!DEFERRED.remember(task, scope.context, geometryEnabled ? renderType : null)) {
            scope.profiler.recordDroppedMeasurement();
        }
    }

    /** Only callbacks associated with a real current-frame renderer acquire a measurement. */
    public static Scope beginDeferred(Object task) {
        var runtime = ClientRuntime.current();
        if (runtime == null || !frameOpen || !worldPass || !runtime.settings().profiling) return null;
        DeferredContexts.Entry entry = DEFERRED.find(task);
        if (entry == null) return null;
        Scope scope = begin(runtime, entry.context());
        if (scope != null) {
            scope.deferred = true;
            if (scope.types != null) scope.types.addAll(entry.renderTypes());
        }
        return scope;
    }

    /** Real completed deferred invocations in this world session, for compatibility diagnostics. */
    public static long measuredDeferredCalls() { return measuredDeferredCalls; }
    public static long queuedDeferredCalls() { return queuedDeferredCalls; }
    public static long renderedDeferredCalls() { return renderedDeferredCalls; }

    public static void endDeferred(Scope scope, boolean failed) {
        if (frameOpen && worldPass) renderedDeferredCalls++;
        end(scope, failed);
    }

    public static void end(Scope scope, boolean failed) {
        if (scope == null) return;
        ACTIVE.set(scope.parent);
        scope.profiler.endCall(scope.token, scope.vertices, scope.bytes,
                scope.types == null ? Set.of() : scope.types, failed);
        if (scope.parent != null) scope.profiler.excludeNestedCall(scope.parent.token, scope.token);
        if (scope.deferred) measuredDeferredCalls++;
    }

    public static boolean recorded(Scope scope) { return scope != null && scope.token.recorded(); }

    public static void vertex(int formatBytes) {
        if (!geometryEnabled) return;
        Scope scope = ACTIVE.get();
        if (scope != null && scope.geometry) { scope.vertices++; scope.bytes += formatBytes; }
    }

    public static void renderType(String name) {
        if (!geometryEnabled) return;
        Scope scope = ACTIVE.get();
        if (scope != null && scope.geometry && scope.types.size() < 32) {
            int bracket = name.indexOf('[');
            name = bracket < 0 ? name : name.substring(0, bracket);
            scope.types.add(name.length() > 128 ? name.substring(0,128) : name);
        }
    }

    public static boolean collectingGeometry() { return geometryEnabled && ACTIVE.get() != null; }

    public static final class Scope {
        private final RenderProfiler profiler;
        private final Scope parent;
        private final boolean geometry;
        private final Set<String> types;
        private final SampleContext context;
        private RenderProfiler.CallToken token;
        private long vertices, bytes;
        private boolean deferred;
        private Scope(RenderProfiler profiler, Scope parent, boolean geometry, SampleContext context) {
            this.profiler = profiler; this.parent = parent; this.geometry = geometry;
            this.types = geometry ? new HashSet<>() : null;
            this.context = context;
        }
    }
}
