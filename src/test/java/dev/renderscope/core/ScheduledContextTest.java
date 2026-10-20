package dev.renderscope.core;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledContextTest {
    private static final SampleContext VISUAL = new SampleContext(
        new InstanceKey(ObjectKind.BLOCK_ENTITY, "minecraft:overworld", "0,64,0"),
        "create:shaft", "create", "create.ShaftVisual", "create",
        new Position(0,64,0), new Bounds(0,64,0,1,65,1));

    @Test void taskQueuedWhileDisabledCannotAcquireAnEnabledFrameWhenAssistedOnTheRenderThread() {
        RenderProfiler profiler = new RenderProfiler();
        ScheduledContext<RenderProfiler.FrameHandle> frames = new ScheduledContext<>(profiler::currentFrame);
        profiler.setEnabled(false);
        profiler.beginFrame();
        Runnable queued = frames.wrap(() -> {
            assertNull(frames.current(), "A null scheduling frame must remain explicitly null");
            assertNull(profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_TICK, frames.current()));
            frames.wrap(() -> assertNull(frames.current(), "Nested tasks inherit unmeasured work")).run();
        });
        profiler.endFrame();
        profiler.setEnabled(true);
        profiler.beginFrame();
        assertNotNull(frames.current());
        queued.run();
        assertNotNull(frames.current(), "The task must restore the outside scheduling context");
        profiler.endFrame();
        assertTrue(profiler.snapshot().instances().isEmpty());
        assertEquals(0, profiler.snapshot().performance().rendererCallsPerFrame());
    }

    @Test void aLateTaskRetainsItsOriginalHandleAndCannotModifyTheFollowingFrame() {
        RenderProfiler profiler = new RenderProfiler();
        ScheduledContext<RenderProfiler.FrameHandle> frames = new ScheduledContext<>(profiler::currentFrame);
        profiler.beginFrame();
        RenderProfiler.FrameHandle original = frames.current();
        Runnable queued = frames.wrap(() -> {
            assertEquals(original.id(), frames.current().id());
            var call = profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, frames.current());
            assertNull(call);
            profiler.endCall(call, 0, 0, Set.of(), false);
        });
        profiler.endFrame();
        profiler.beginFrame();
        RenderProfiler.FrameHandle current = frames.current();
        assertNotEquals(original.id(), current.id());
        queued.run();
        assertEquals(current.id(), frames.current().id());
        profiler.endFrame();
        assertTrue(profiler.snapshot().instances().isEmpty());
        assertEquals(1, profiler.snapshot().droppedMeasurements());
    }

    @Test void explicitNullSurvivesWorkerExecutionAndExceptionWithoutLeakingThreadContext() throws Exception {
        AtomicReference<String> outside = new AtomicReference<>();
        ScheduledContext<String> contexts = new ScheduledContext<>(outside::get);
        Runnable queued = contexts.wrap(() -> {
            assertNull(contexts.current());
            throw new IllegalStateException("real task failure");
        });
        outside.set("new frame");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                assertThrows(IllegalStateException.class, queued::run);
                assertEquals("new frame", contexts.current());
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start(); worker.join();
        assertNull(failure.get());
        assertEquals("new frame", contexts.current());
    }
}
