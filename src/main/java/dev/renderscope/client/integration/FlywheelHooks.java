package dev.renderscope.client.integration;

import dev.renderscope.client.ClientRuntime;
import dev.renderscope.client.FlywheelGpuTimers;
import dev.renderscope.client.RenderHooks;
import dev.renderscope.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;

/** Optional Flywheel CPU adapters. This class itself has no Flywheel linkage. */
public final class FlywheelHooks {
    private static final WeakIdentityMap<VisualEntry> VISUALS = new WeakIdentityMap<>(8192);
    private static final ScheduledContext<RenderProfiler.FrameHandle> TASK_FRAMES =
        new ScheduledContext<>(FlywheelHooks::currentClientFrame);
    private static final AtomicLong SESSION = new AtomicLong();
    private static final AtomicLong FRAME_CALLS = new AtomicLong();
    private static final AtomicLong TICK_CALLS = new AtomicLong();
    private static final AtomicLong UPDATE_CALLS = new AtomicLong();
    private static final AtomicLong BATCH_CALLS = new AtomicLong();

    private FlywheelHooks() { }

    public static void associate(Object visual, Object original) {
        if (visual == null) return;
        long session = SESSION.get();
        SampleContext context = context(original, visual);
        if (context == null || session != SESSION.get()) return; // Virtual BEs and old-world factories are excluded.
        VisualEntry previous = VISUALS.get(visual);
        if (previous != null) {
            if (!previous.active) return; // A visual shared by different objects has no unambiguous owner.
            previous.active = false;
            if (!previous.context.key().equals(context.key()) || !previous.context.registryId().equals(context.registryId())) {
                var runtime = ClientRuntime.current();
                if (runtime != null) runtime.profiler().recordDroppedMeasurement();
                return;
            }
        }
        VisualEntry entry = new VisualEntry(new WeakReference<>(original), context, session);
        if (!VISUALS.put(visual, entry)) {
            var runtime = ClientRuntime.current();
            if (runtime != null) runtime.profiler().recordDroppedMeasurement();
        }
        // A worker may finish its factory while the render thread disconnects.
        if (session != SESSION.get()) {
            entry.active = false;
            if (VISUALS.get(visual) == entry) VISUALS.remove(visual);
        }
    }

    public static void forget(Object visual) {
        VisualEntry entry = VISUALS.remove(visual);
        if (entry != null) entry.active = false;
    }

    private static SampleContext context(Object original, Object implementation) {
        if (original instanceof BlockEntity block) return RenderHooks.context(block, implementation);
        if (original instanceof Entity entity) return RenderHooks.context(entity, implementation);
        return null;
    }

    /** Refresh moving entity values on the client thread, never by traversing a world on workers. */
    public static void refreshEntityContexts() {
        for (VisualEntry entry : VISUALS.values()) {
            Object original = entry.original.get();
            if (!(original instanceof Entity entity) || !entry.active) continue;
            SampleContext previous = entry.context;
            var box = entity.getBoundingBox();
            entry.context = new SampleContext(previous.key(), previous.registryId(), previous.registryOwner(),
                previous.rendererClass(), previous.rendererOwner(),
                new Position(entity.getX(), entity.getY(), entity.getZ()),
                new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
    }

    public static RenderProfiler.FrameHandle scheduledFrame() {
        return TASK_FRAMES.current();
    }

    private static RenderProfiler.FrameHandle currentClientFrame() {
        var runtime = ClientRuntime.current();
        // An untagged worker must never attach old work to whatever frame happens to be current.
        return runtime != null && Minecraft.getInstance().isSameThread() ? runtime.profiler().currentFrame() : null;
    }

    /** Both Flywheel serial/parallel executors propagate the scheduling frame through task trees. */
    public static Runnable wrapTask(Runnable command) {
        return TASK_FRAMES.wrap(command);
    }

    /** Immutable task metadata may safely cross threads without retaining a visual or world. */
    public static TaskContext taskContext(Object visual, MeasurementSource source) {
        VisualEntry entry = VISUALS.get(visual);
        if (entry == null || !entry.active || entry.session != SESSION.get()) return null;
        return new TaskContext(entry.context, source, scheduledFrame(), entry, entry.session);
    }

    public static VisualCall begin(Object visual, MeasurementSource source) { return begin(taskContext(visual, source)); }

    public static VisualCall beginCreation(Object original, Object factory) {
        SampleContext context = context(original, factory);
        return context == null ? null : begin(new TaskContext(context, MeasurementSource.FLYWHEEL_VISUAL_CREATE,
            scheduledFrame(), null, SESSION.get()));
    }

    public static VisualCall begin(TaskContext task) {
        if (task == null || task.session != SESSION.get() || task.entry != null && !task.entry.active) return null;
        RenderHooks.Scope scope = RenderHooks.beginVisual(task.context, task.source, task.frame);
        return scope == null ? null : new VisualCall(scope, task.source, task.session);
    }

    public static void end(VisualCall call, boolean failed) {
        if (call == null) return;
        RenderHooks.end(call.scope, failed);
        if (call.session != SESSION.get() || !RenderHooks.recorded(call.scope)) return;
        switch (call.source) {
            case FLYWHEEL_VISUAL_FRAME -> FRAME_CALLS.incrementAndGet();
            case FLYWHEEL_VISUAL_TICK -> TICK_CALLS.incrementAndGet();
            case FLYWHEEL_VISUAL_UPDATE, FLYWHEEL_VISUAL_LIGHT -> UPDATE_CALLS.incrementAndGet();
            default -> { }
        }
    }

    public static void measure(Object visual, MeasurementSource source, Runnable work) {
        measure(taskContext(visual, source), work);
    }

    public static void measure(TaskContext task, Runnable work) {
        VisualCall call = begin(task);
        boolean failed = true;
        try { work.run(); failed = false; }
        finally { end(call, failed); }
    }

    /** Shared upload/cull/draw submission CPU and asynchronous GPU queries stay outside objects. */
    public static BatchCall beginBatch() {
        var runtime = ClientRuntime.current();
        if (runtime == null || ShaderPasses.isShadowPass()) return null;
        RenderProfiler.PhaseToken cpu = runtime.profiler().beginPhase("flywheel:batch_submit", MeasurementSource.FLYWHEEL_BATCH_SUBMIT);
        return new BatchCall(runtime.profiler(), cpu, FlywheelGpuTimers.begin(), SESSION.get());
    }

    public static void endBatch(BatchCall call, boolean failed) {
        if (call == null) return;
        try { FlywheelGpuTimers.end(call.gpu); }
        finally {
            call.profiler.endPhase(call.cpu, failed);
            if (call.cpu != null && call.session == SESSION.get()) BATCH_CALLS.incrementAndGet();
        }
    }

    public static int trackedVisuals() { return VISUALS.size(); }
    public static long measuredFrameCalls() { return FRAME_CALLS.get(); }
    public static long measuredTickCalls() { return TICK_CALLS.get(); }
    public static long measuredUpdateCalls() { return UPDATE_CALLS.get(); }
    public static long sharedBatchCalls() { return BATCH_CALLS.get(); }

    public static void clear() {
        SESSION.incrementAndGet();
        for (VisualEntry entry : VISUALS.values()) entry.active = false;
        VISUALS.clear(); TASK_FRAMES.clear();
        FRAME_CALLS.set(0); TICK_CALLS.set(0); UPDATE_CALLS.set(0); BATCH_CALLS.set(0);
    }

    private static final class VisualEntry {
        final WeakReference<Object> original;
        volatile SampleContext context;
        final long session;
        volatile boolean active = true;
        VisualEntry(WeakReference<Object> original, SampleContext context, long session) {
            this.original = original; this.context = context; this.session = session;
        }
    }

    public static final class TaskContext {
        final SampleContext context;
        final MeasurementSource source;
        final RenderProfiler.FrameHandle frame;
        final VisualEntry entry;
        final long session;
        private TaskContext(SampleContext context, MeasurementSource source, RenderProfiler.FrameHandle frame,
                            VisualEntry entry, long session) {
            this.context = context; this.source = source; this.frame = frame; this.entry = entry; this.session = session;
        }
    }

    public record VisualCall(RenderHooks.Scope scope, MeasurementSource source, long session) { }
    public record BatchCall(RenderProfiler profiler, RenderProfiler.PhaseToken cpu,
                            FlywheelGpuTimers.Token gpu, long session) { }
}
