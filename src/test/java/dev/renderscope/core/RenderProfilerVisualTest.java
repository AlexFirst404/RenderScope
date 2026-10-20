package dev.renderscope.core;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RenderProfilerVisualTest {
    private static final SampleContext RENDERER = context("create.client.ShaftRenderer");
    private static final SampleContext VISUAL = context("create.client.ShaftVisual");

    private static SampleContext context(String implementation) {
        return new SampleContext(new InstanceKey(ObjectKind.BLOCK_ENTITY, "minecraft:overworld", "4,64,-3"),
            "create:shaft", "create", implementation, "create", new Position(4, 64, -3), new Bounds(4,64,-3,5,65,-2));
    }

    @Test void mixedRendererAndVisualKeepSeparateImplementationsAndShareOneWorldInstance() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        f.call(RENDERER, MeasurementSource.RENDERER, 1_000_000);
        f.call(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_TICK, 5_000_000);
        f.finish();
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(1, snapshot.instances().size());
        assertEquals(6, snapshot.instances().get(0).stats().averageMs());
        assertEquals(1, snapshot.instances().get(0).stats().sources().get(MeasurementSource.RENDERER).averageMs());
        assertEquals(5, snapshot.instances().get(0).stats().sources().get(MeasurementSource.FLYWHEEL_VISUAL_TICK).averageMs());
        assertEquals(2, snapshot.renderers().size());
        assertEquals(5, snapshot.renderers().stream().filter(r -> r.id().endsWith("Visual")).findFirst().orElseThrow().stats().averageMs());
        assertEquals(1, snapshot.renderers().stream().filter(r -> r.id().endsWith("Renderer")).findFirst().orElseThrow().stats().averageMs());
        assertTrue(snapshot.renderers().stream().allMatch(r -> r.stats().instances() == 1));
        assertEquals(0, snapshot.instances().get(0).stats().verticesPerFrame());
        assertEquals(0, snapshot.instances().get(0).stats().estimatedBytesPerFrame());
    }

    @Test void sourceAveragesIncludeZeroFramesAndRollingExpiryRemovesOnlyExpiredSources() {
        Fixture f = new Fixture();
        f.profiler.setSampleWindowSeconds(1);
        f.profiler.beginFrame();
        f.call(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_TICK, 2_000_000);
        f.finish();
        f.clock.addAndGet(600_000_000);
        f.profiler.beginFrame();
        f.call(RENDERER, MeasurementSource.RENDERER, 4_000_000);
        f.finish();
        Statistics stats = f.profiler.snapshot().instances().get(0).stats();
        assertEquals(1, stats.sources().get(MeasurementSource.FLYWHEEL_VISUAL_TICK).averageMs());
        assertEquals(2, stats.sources().get(MeasurementSource.RENDERER).averageMs());
        assertThrows(UnsupportedOperationException.class, () -> stats.sources().clear());
        f.clock.addAndGet(500_000_000);
        ProfilerSnapshot after = f.profiler.snapshot();
        assertFalse(after.instances().get(0).stats().sources().containsKey(MeasurementSource.FLYWHEEL_VISUAL_TICK));
        assertEquals(4, after.instances().get(0).stats().sources().get(MeasurementSource.RENDERER).averageMs());
        assertEquals(1, after.renderers().size());
        assertEquals(RENDERER.rendererClass(), after.renderers().get(0).id());
    }

    @Test void sharedBatchPhaseHasRealTimeButNoArtificialWorldObjectOrMod() {
        Fixture f = new Fixture();
        f.profiler.startCapture(0);
        f.profiler.beginFrame();
        RenderProfiler.PhaseToken phase = f.profiler.beginPhase("flywheel:batch_submit", MeasurementSource.FLYWHEEL_BATCH_SUBMIT);
        f.clock.addAndGet(7_000_000);
        f.profiler.endPhase(phase, false);
        f.finish();
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(7, snapshot.phases().get(0).stats().averageMs());
        assertEquals(0, snapshot.phases().get(0).stats().instances());
        assertEquals(1, snapshot.phases().get(0).stats().sources().get(MeasurementSource.FLYWHEEL_BATCH_SUBMIT).calls());
        assertTrue(snapshot.instances().isEmpty());
        assertTrue(snapshot.mods().isEmpty());
        assertEquals(0, snapshot.performance().measuredRenderMs());
        assertEquals(0, snapshot.performance().rendererCallsPerFrame());
        PhaseMetric captured = f.profiler.stopCapture().phases().get(0);
        assertEquals(snapshot.phases().get(0).id(), captured.id());
        assertEquals(7, captured.stats().averageMs());
        assertEquals(7, captured.stats().p95Ms(), 0.1, "Capture percentiles use the bounded logarithmic histogram");
        assertEquals(snapshot.phases().get(0).stats().sources(), captured.stats().sources());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.phases().clear());
    }

    @Test void scheduledFrameCannotAttachLateWorkToTheNextFrame() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        RenderProfiler.FrameHandle first = f.profiler.currentFrame();
        RenderProfiler.CallToken unfinished = f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, first);
        f.finish();
        f.profiler.beginFrame();
        assertNull(f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, first));
        f.profiler.endCall(unfinished, 0, 0, Set.of(), false);
        f.call(RENDERER, MeasurementSource.RENDERER, 1_000_000);
        f.finish();
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(1, snapshot.instances().get(0).stats().calls());
        assertFalse(snapshot.instances().get(0).stats().sources().containsKey(MeasurementSource.FLYWHEEL_VISUAL_FRAME));
        assertEquals(2, snapshot.droppedMeasurements());
    }

    @Test void oldWorldAndForeignProfilerHandlesAreRejected() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        RenderProfiler.FrameHandle old = f.profiler.currentFrame();
        f.profiler.clear();
        f.profiler.beginFrame();
        assertNull(f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, old));
        RenderProfiler other = new RenderProfiler();
        other.beginFrame();
        assertNull(f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, other.currentFrame()));
        f.finish();
        assertTrue(f.profiler.snapshot().instances().isEmpty());
        assertEquals(0, f.profiler.snapshot().droppedMeasurements());
    }

    @Test void workerUsesSchedulingFrameAndItsOwnThreadCpuClock() throws Exception {
        Fixture f = new Fixture();
        f.profiler.setCpuTimingEnabled(true);
        f.profiler.beginFrame();
        RenderProfiler.FrameHandle frame = f.profiler.currentFrame();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                f.cpu.set(100L);
                var token = f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, frame);
                f.clock.addAndGet(3_000_000);
                f.cpu.set(2_000_100L);
                f.profiler.endCall(token, 0, 0, Set.of(), false);
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start(); worker.join();
        assertNull(failure.get());
        f.finish();
        SourceMetric source = f.profiler.snapshot().instances().get(0).stats().sources().get(MeasurementSource.FLYWHEEL_VISUAL_FRAME);
        assertEquals(3, source.averageMs());
        assertEquals(2, source.cpuMs());
        assertEquals(1, source.calls());
    }

    @Test void completionOnADifferentThreadIsRejectedRatherThanMismeasuringCpu() throws Exception {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        var token = f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, f.profiler.currentFrame());
        Thread worker = new Thread(() -> f.profiler.endCall(token, 0, 0, Set.of(), false));
        worker.start(); worker.join();
        f.finish();
        assertTrue(f.profiler.snapshot().instances().isEmpty());
        assertEquals(1, f.profiler.snapshot().droppedMeasurements());
    }

    @Test void nestedPlanIntervalsExcludeChildrenAndUnrelatedCompletionWorkExactlyOnce() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        var frame = f.profiler.currentFrame();
        var parent = f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, frame);
        f.clock.addAndGet(1_000_000);
        var child = f.profiler.beginCall(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, frame);
        f.clock.addAndGet(1_000_000);
        var excluded = f.profiler.beginExcludedInterval(child);
        f.call(RENDERER, MeasurementSource.FLYWHEEL_VISUAL_TICK, 3_000_000);
        f.profiler.endExcludedInterval(excluded);
        f.profiler.endExcludedInterval(excluded);
        f.clock.addAndGet(1_000_000);
        f.profiler.endCall(child, 0, 0, Set.of(), false);
        f.profiler.excludeNestedCall(parent, child);
        f.clock.addAndGet(1_000_000);
        f.profiler.endCall(parent, 0, 0, Set.of(), false);
        f.finish();
        Statistics stats = f.profiler.snapshot().instances().get(0).stats();
        assertEquals(7, stats.averageMs());
        assertEquals(4, stats.sources().get(MeasurementSource.FLYWHEEL_VISUAL_FRAME).averageMs());
        assertEquals(3, stats.sources().get(MeasurementSource.FLYWHEEL_VISUAL_TICK).averageMs());
        assertEquals(3, stats.calls());
    }

    @Test void captureKeepsVisualSourcesAndSharedPhasesAfterRollingWindowExpires() {
        Fixture f = new Fixture();
        f.profiler.setSampleWindowSeconds(1);
        f.profiler.startCapture(0);
        for (int i = 0; i < 10; i++) {
            f.profiler.beginFrame();
            f.call(VISUAL, MeasurementSource.FLYWHEEL_VISUAL_FRAME, 2_000_000);
            var phase = f.profiler.beginPhase("flywheel:batch_submit", MeasurementSource.FLYWHEEL_BATCH_SUBMIT);
            f.clock.addAndGet(4_000_000);
            f.profiler.endPhase(phase, i == 0);
            f.finish();
            f.clock.addAndGet(500_000_000);
        }
        CaptureReport capture = f.profiler.stopCapture();
        assertEquals(10, capture.frames());
        assertEquals(2, capture.instances().get(0).stats().sources().get(MeasurementSource.FLYWHEEL_VISUAL_FRAME).averageMs());
        assertEquals(10, capture.phases().get(0).stats().calls());
        assertEquals(1, capture.phases().get(0).stats().failures());
        assertEquals(4, capture.phases().get(0).stats().averageMs());
        assertTrue(f.profiler.snapshot().performance().frames() < 10);
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final ThreadLocal<Long> cpu = ThreadLocal.withInitial(() -> 0L);
        final RenderProfiler profiler = new RenderProfiler(clock::get, cpu::get, () -> 0L);
        void call(SampleContext context, MeasurementSource source, long elapsed) {
            var token = profiler.beginCall(context, source, profiler.currentFrame());
            clock.addAndGet(elapsed);
            profiler.endCall(token, 0, 0, Set.of(), false);
        }
        void finish() { clock.addAndGet(10_000_000); profiler.endFrame(10_000_000); }
    }
}
