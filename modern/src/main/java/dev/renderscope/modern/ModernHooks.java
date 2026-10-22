package dev.renderscope.modern;

import dev.renderscope.client.ProfilerSettings;
import dev.renderscope.core.*;
import dev.renderscope.client.platform.PlatformServices;
import dev.renderscope.client.integration.ShaderPasses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.lang.ref.WeakReference;
import java.util.Set;

/** State associations contain values and an opaque frame handle; neither world objects nor renderers are retained. */
public final class ModernHooks {
    public static final RenderProfiler PROFILER = new RenderProfiler();
    public static final ProfilerSettings SETTINGS = ProfilerSettings.load(
        PlatformServices.current().configDirectory().resolve("renderscope.json"));
    private static final WeakIdentityMap<StateContext> STATES = new WeakIdentityMap<>(8192);
    private static final ThreadLocal<Integer> WORLD_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);
    private static boolean hadWorld;
    private static long frameStarted;
    private static long extracted, submitted;

    private ModernHooks() { }
    public static void applySettings() {
        SETTINGS.sanitize();
        PROFILER.setEnabled(SETTINGS.profiling);
        PROFILER.setSampleWindowSeconds(SETTINGS.sampleWindowSeconds);
        PROFILER.setCpuTimingEnabled(SETTINGS.cpuTiming);
        SETTINGS.save();
    }
    public static void beginFrame() {
        ClientLevel currentWorld = Minecraft.getInstance().level;
        if (world.get() != currentWorld || hadWorld && currentWorld == null) {
            PROFILER.clear(); STATES.clear(); ModernOverlay.clear();
            world = new WeakReference<>(currentWorld);
            extracted = submitted = 0;
        }
        hadWorld = currentWorld != null;
        STATES.clear(); // Vanilla extracts new states before submitting them in this same frame.
        frameStarted = System.nanoTime();
        WORLD_DEPTH.remove(); CURRENT.remove();
        PROFILER.beginFrame();
    }
    public static void endFrame() {
        PROFILER.endFrame(Math.max(1, System.nanoTime() - frameStarted));
        WORLD_DEPTH.remove(); CURRENT.remove();
    }
    public static void enterWorld() { WORLD_DEPTH.set(WORLD_DEPTH.get() + 1); }
    public static void leaveWorld() {
        int depth = WORLD_DEPTH.get() - 1;
        if (depth <= 0) WORLD_DEPTH.remove(); else WORLD_DEPTH.set(depth);
    }
    public static Scope beginEntity(Entity entity, Object renderer) {
        if (!SETTINGS.profiling || WORLD_DEPTH.get() == 0 || ShaderPasses.isShadowPass()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || entity.level() != mc.level
            || mc.level.getEntity(entity.getId()) != entity) return null;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        AABB box = entity.getBoundingBox();
        SampleContext context = new SampleContext(entityKey(entity), id.toString(), id.getNamespace(),
            renderer.getClass().getName(), ModCatalog.implementationOwner(renderer.getClass()),
            new Position(entity.getX(), entity.getY(), entity.getZ()), bounds(box));
        return begin(context, MeasurementSource.RENDER_EXTRACTION, PROFILER.currentFrame());
    }
    public static Scope beginBlockEntity(BlockEntity entity, Object renderer) {
        if (!SETTINGS.profiling || WORLD_DEPTH.get() == 0 || ShaderPasses.isShadowPass()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || entity.getLevel() != mc.level
            || mc.level.getBlockEntity(entity.getBlockPos()) != entity) return null;
        var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
        var pos = entity.getBlockPos();
        SampleContext context = new SampleContext(blockKey(entity), id.toString(), id.getNamespace(),
            renderer.getClass().getName(), ModCatalog.implementationOwner(renderer.getClass()),
            new Position(pos.getX(), pos.getY(), pos.getZ()), new Bounds(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
        return begin(context, MeasurementSource.RENDER_EXTRACTION, PROFILER.currentFrame());
    }
    public static void associate(Object renderState, Scope extraction) {
        if (renderState != null && extraction != null
            && !STATES.put(renderState, new StateContext(extraction.context, extraction.frame))) PROFILER.recordDroppedMeasurement();
    }
    public static Scope beginSubmit(Object renderState, Object renderer) {
        if (!SETTINGS.profiling || WORLD_DEPTH.get() == 0 || ShaderPasses.isShadowPass()) return null;
        StateContext state = STATES.get(renderState);
        if (state == null) return null;
        SampleContext old = state.context;
        SampleContext context = new SampleContext(old.key(), old.registryId(), old.registryOwner(),
            renderer.getClass().getName(), ModCatalog.implementationOwner(renderer.getClass()), old.position(), old.bounds());
        return begin(context, MeasurementSource.RENDER_SUBMISSION, state.frame);
    }
    private static Scope begin(SampleContext context, MeasurementSource source, RenderProfiler.FrameHandle frame) {
        var token = PROFILER.beginCall(context, source, frame);
        if (token == null) return null;
        Scope scope = new Scope(context, frame, token, CURRENT.get(), source);
        CURRENT.set(scope);
        return scope;
    }
    public static void end(Scope scope, boolean failed) {
        if (scope == null) return;
        try {
            PROFILER.endCall(scope.token, 0, 0, Set.of(), failed);
            if (scope.parent != null) PROFILER.excludeNestedCall(scope.parent.token, scope.token);
            if (scope.token != null) {
                if (scope.source == MeasurementSource.RENDER_EXTRACTION) extracted++; else submitted++;
            }
        } finally {
            if (scope.parent == null) CURRENT.remove(); else CURRENT.set(scope.parent);
        }
    }
    public static InstanceKey entityKey(Entity entity) {
        return new InstanceKey(ObjectKind.ENTITY, entity.level().dimension().identifier().toString(),
            entity.getId() + "#" + entity.getUUID());
    }
    public static InstanceKey blockKey(BlockEntity entity) {
        return new InstanceKey(ObjectKind.BLOCK_ENTITY, entity.getLevel().dimension().identifier().toString(),
            Long.toString(entity.getBlockPos().asLong())).withRegistry(
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
    }
    public static long extractionCalls() { return extracted; }
    public static long submissionCalls() { return submitted; }
    private static Bounds bounds(AABB box) { return new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ); }
    private record StateContext(SampleContext context, RenderProfiler.FrameHandle frame) { }
    public static final class Scope {
        private final SampleContext context;
        private final RenderProfiler.FrameHandle frame;
        private final RenderProfiler.CallToken token;
        private final Scope parent;
        private final MeasurementSource source;
        private Scope(SampleContext context, RenderProfiler.FrameHandle frame, RenderProfiler.CallToken token,
                      Scope parent, MeasurementSource source) {
            this.context = context; this.frame = frame; this.token = token; this.parent = parent; this.source = source;
        }
    }
}
