package dev.renderscope.core;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RenderProfilerTest {
    private static final SampleContext CHEST = context(ObjectKind.BLOCK_ENTITY, "0,64,0", "minecraft:chest");
    private static final SampleContext COW = context(ObjectKind.ENTITY, "42", "minecraft:cow");

    private static SampleContext context(ObjectKind kind, String identity, String registryId) {
        return new SampleContext(new InstanceKey(kind, "minecraft:overworld", identity), registryId,
            registryId.substring(0, registryId.indexOf(':')), "compat.client.CustomRenderer", "compat",
            new Position(0, 64, 0), new Bounds(0, 64, 0, 1, 65, 1));
    }

    @Test
    void combinesCallsInOneFrameAndIncludesAbsentFramesInTypeAndInstanceStatistics() {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) {
            f.profiler.beginFrame();
            if (i == 0) {
                f.call(CHEST, 1_000_000, 4, 128, false);
                f.call(CHEST, 3_000_000, 8, 256, true);
            }
            f.finish(10_000_000);
        }
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        Statistics stats = snapshot.objects().get(0).stats();
        assertEquals(0.2, stats.averageMs(), 1e-9);
        assertEquals(0, stats.p95Ms(), "19 of 20 frames did not render this type");
        assertEquals(4, stats.peakMs());
        assertEquals(2, stats.calls());
        assertEquals(0.1, stats.callsPerFrame(), 1e-9);
        assertEquals(1, stats.failures());
        assertEquals(0.6, stats.verticesPerFrame(), 1e-9);
        assertEquals(19.2, stats.estimatedBytesPerFrame(), 1e-9);
        assertEquals(1, stats.instances());
        assertEquals(stats, snapshot.instances().get(0).stats());
        assertEquals(100, snapshot.performance().fps(), 1e-9);
    }

    @Test
    void aggregatesDifferentInstancesAndCategoriesWithoutLosingRendererOwnership() {
        Fixture f = new Fixture();
        SampleContext secondChest = context(ObjectKind.BLOCK_ENTITY, "1,64,0", "minecraft:chest");
        f.profiler.beginFrame();
        f.call(CHEST, 1_000_000, 4, 128, false);
        f.call(secondChest, 2_000_000, 4, 128, false);
        f.call(COW, 4_000_000, 4, 128, false);
        f.finish(10_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(1, snapshot.mods().size());
        assertEquals(7, snapshot.mods().get(0).stats().averageMs());
        assertEquals(3, snapshot.mods().get(0).stats().instances());
        assertEquals(2, snapshot.objects().size());
        assertEquals("minecraft", snapshot.renderers().get(0).registryOwner());
        assertEquals("compat", snapshot.renderers().get(0).rendererOwner());
        assertEquals(2, snapshot.performance().renderedBlockEntitiesPerFrame());
        assertEquals(1, snapshot.performance().renderedEntitiesPerFrame());
        assertEquals(3, snapshot.performance().blockEntityRenderMs());
        assertEquals(4, snapshot.performance().entityRenderMs());
    }

    @Test
    void framePercentilesAndOnePercentLowUseActualSlowestFrames() {
        Fixture f = new Fixture();
        for (int i = 0; i < 100; i++) {
            f.profiler.beginFrame();
            f.finish(i == 99 ? 100_000_000 : 10_000_000);
        }
        Performance p = f.profiler.snapshot().performance();
        assertEquals(10, p.p50FrameMs());
        assertEquals(10, p.p95FrameMs());
        assertEquals(10, p.p99FrameMs());
        assertEquals(100, p.worstFrameMs());
        assertEquals(10, p.onePercentLowFps());
        assertEquals(10.9, p.averageFrameMs(), 1e-9);
    }

    @Test
    void rollingDataExpiresAndSnapshotReusesAnImmutableValueFor250Milliseconds() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        f.call(CHEST, 1_000_000, 4, 128, false);
        f.finish(10_000_000);
        ProfilerSnapshot before = f.profiler.snapshot();
        f.profiler.beginFrame();
        f.call(COW, 2_000_000, 4, 128, false);
        f.finish(10_000_000);
        assertSame(before, f.profiler.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> before.instances().clear());
        assertThrows(UnsupportedOperationException.class, () -> before.instances().get(0).stats().renderTypes().add("fake"));
        f.clock.addAndGet(6_000_000_000L);
        assertEquals(0, f.profiler.snapshot().performance().frames());
        assertTrue(f.profiler.snapshot().instances().isEmpty());
        assertEquals(1, before.instances().size(), "A previously published snapshot stays unchanged");
    }

    @Test
    void captureCoversAllFramesAfterRollingWindowHasExpiredAndKeepsItsOwnAttribution() {
        Fixture f = new Fixture();
        f.profiler.startCapture(0);
        for (int i = 0; i < 12; i++) {
            f.profiler.beginFrame();
            f.call(CHEST, 2_000_000, 4, 128, false);
            f.finish(500_000_000);
        }
        CaptureReport capture = f.profiler.stopCapture();
        assertNotNull(capture);
        assertEquals(12, capture.frames());
        assertEquals(2, capture.objects().get(0).stats().averageMs(), 1e-9);
        assertEquals(2, capture.objects().get(0).stats().p95Ms(), 0.05);
        assertEquals(12, capture.objects().get(0).stats().calls());
        assertTrue(f.profiler.snapshot().performance().frames() < capture.frames());
        assertSame(capture, f.profiler.lastCapture());
        assertFalse(f.profiler.snapshot().capture().active());
        assertTrue(capture.durationSeconds() >= 6);
    }

    @Test
    void fixedCaptureCompletesAutomaticallyAndZerosRemainPartOfItsPercentiles() {
        Fixture f = new Fixture();
        f.profiler.startCapture(5);
        for (int i = 0; i < 20; i++) {
            f.profiler.beginFrame();
            if (i == 0) f.call(CHEST, 1_000_000, 4, 128, false);
            f.finish(250_000_000);
        }
        CaptureReport capture = f.profiler.lastCapture();
        assertNotNull(capture);
        assertEquals(20, capture.frames());
        assertEquals(0.05, capture.instances().get(0).stats().averageMs(), 1e-9);
        assertEquals(0, capture.instances().get(0).stats().p95Ms());
        assertFalse(f.profiler.snapshot().capture().active());
    }

    @Test
    void cpuFailuresAndTimingOnlyAreRealMeasurementsAndDuplicateCompletionsAreIgnored() {
        Fixture f = new Fixture();
        f.profiler.setCpuTimingEnabled(true);
        f.profiler.beginFrame();
        RenderProfiler.CallToken token = f.profiler.beginCall(CHEST);
        f.clock.addAndGet(2_000_000);
        f.profiler.endCall(token, 0, 0, Set.of(), true);
        f.profiler.endCall(token, 100, 100, Set.of("fake"), false);
        f.finish(10_000_000);
        Statistics stats = f.profiler.snapshot().instances().get(0).stats();
        assertEquals(2, stats.cpuMs());
        assertEquals(1, stats.failures());
        assertEquals(1, stats.calls());
        assertEquals(0, stats.verticesPerFrame());
        assertTrue(stats.renderTypes().isEmpty());
    }

    @Test
    void disabledProfilingKeepsFrameMetricsButCollectsNoRendererData() {
        Fixture f = new Fixture();
        f.profiler.setEnabled(false);
        f.profiler.beginFrame();
        assertNull(f.profiler.beginCall(CHEST));
        f.profiler.endCall(null, 0, 0, null, false);
        f.finish(10_000_000);
        assertFalse(f.profiler.snapshot().enabled());
        assertEquals(100, f.profiler.snapshot().performance().fps());
        assertTrue(f.profiler.snapshot().instances().isEmpty());
    }

    @Test
    void worldResetReleasesCapturesAndIgnoresTokensFromOldSession() {
        Fixture f = new Fixture();
        f.profiler.startCapture(0);
        f.profiler.beginFrame();
        RenderProfiler.CallToken old = f.profiler.beginCall(CHEST);
        f.profiler.clear();
        f.profiler.beginFrame();
        f.clock.addAndGet(1_000_000);
        f.profiler.endCall(old, 4, 128, Set.of("solid"), false);
        f.finish(10_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertTrue(snapshot.instances().isEmpty());
        assertFalse(snapshot.capture().active());
        assertNull(f.profiler.lastCapture());
        assertEquals(0, snapshot.droppedMeasurements());
    }

    @Test
    void spikeTimelinePreservesTheActualModAndWorstInstance() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        f.finish(100_000_000);
        f.profiler.beginFrame();
        f.finish(10_000_000);
        // Establish a low baseline, then ensure the expensive frame gets its own timeline bucket.
        for (int i = 0; i < 100; i++) {
            f.profiler.beginFrame();
            f.finish(10_000_000);
        }
        f.clock.addAndGet(100_000_000);
        f.profiler.beginFrame();
        f.call(CHEST, 30_000_000, 4, 128, false);
        f.call(COW, 10_000_000, 4, 128, false);
        f.finish(100_000_000);
        FramePoint spike = f.profiler.snapshot().timeline().stream().filter(FramePoint::spike).findFirst().orElseThrow();
        assertEquals(100, spike.frameMs());
        assertEquals(40, spike.topModMs().get("minecraft"));
        assertEquals(CHEST.key(), spike.worstInstance().context().key());
        assertEquals(30, spike.worstInstance().stats().averageMs());
    }

    @Test
    void perFrameAndRollingBoundsAreEnforcedAndReported() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        for (int i = 0; i < 4097; i++) f.call(context(ObjectKind.ENTITY, Integer.toString(i), "minecraft:cow"), 1, 0, 0, false);
        f.finish(10_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(4096, snapshot.instances().size());
        assertEquals(4097, snapshot.performance().rendererCallsPerFrame());
        assertEquals(1, snapshot.droppedMeasurements());
        f.profiler.clear();
        for (int i = 0; i < 8193; i++) {
            f.profiler.beginFrame();
            f.finish(1);
        }
        assertEquals(8192, f.profiler.snapshot().performance().frames());
        assertTrue(f.profiler.snapshot().droppedMeasurements() > 0);
    }

    @Test
    void expiringAFrameUpdatesPeaksTypesInstanceCountsAndGroupTotalsIncrementally() {
        Fixture f = new Fixture();
        f.profiler.setSampleWindowSeconds(1);
        f.profiler.beginFrame();
        f.call(CHEST, 6_000_000, 12, 384, false);
        f.call(COW, 2_000_000, 4, 128, false);
        f.finish(500_000_000);
        f.profiler.beginFrame();
        RenderProfiler.CallToken token = f.profiler.beginCall(CHEST);
        f.clock.addAndGet(1_000_000);
        f.profiler.endCall(token, 4, 128, Set.of("translucent"), false);
        f.finish(400_000_000);
        assertEquals(2, f.profiler.snapshot().instances().size());
        f.clock.addAndGet(700_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(1, snapshot.performance().frames());
        assertEquals(1, snapshot.instances().size());
        assertEquals(1, snapshot.objects().size());
        assertEquals(1, snapshot.mods().get(0).stats().instances());
        assertEquals(1, snapshot.mods().get(0).stats().averageMs());
        assertEquals(1, snapshot.mods().get(0).stats().peakMs());
        assertEquals(Set.of("translucent"), snapshot.mods().get(0).stats().renderTypes());
        assertEquals(400, snapshot.performance().worstFrameMs());
        assertEquals(1, snapshot.performance().rendererCallsPerFrame());
        assertEquals(4, snapshot.performance().verticesPerFrame());
    }

    @Test
    void rollingInstanceChurnIsBoundedByRetainedFramesWhileManualCapturesHaveAFixedKeyLimit() {
        Fixture f = new Fixture();
        f.profiler.setSampleWindowSeconds(1);
        f.profiler.startCapture(0);
        for (int frame = 0; frame < 5; frame++) {
            f.profiler.beginFrame();
            for (int i = 0; i < 1000; i++) {
                f.call(context(ObjectKind.ENTITY, Integer.toString(frame * 1000 + i), "minecraft:cow"), 1, 0, 0, false);
            }
            f.finish(100_000_000);
        }
        assertEquals(5000, f.profiler.snapshot().instances().size());
        f.clock.addAndGet(600_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(4000, snapshot.instances().size());
        assertEquals(4, snapshot.performance().frames());
        assertEquals(4000, snapshot.mods().get(0).stats().instances());
        CaptureReport capture = f.profiler.stopCapture();
        assertEquals(4096, capture.instances().size());
        assertEquals(5000, capture.mods().get(0).stats().calls());
        assertEquals(904, capture.droppedMeasurements());
    }

    @Test
    void nestedCallsExcludeTheFullChildIntervalIncludingGrandchildrenExactlyOnce() {
        Fixture f = new Fixture();
        f.profiler.setCpuTimingEnabled(true);
        SampleContext grandchildContext = context(ObjectKind.ENTITY, "grandchild", "custom:grandchild");
        f.profiler.beginFrame();
        RenderProfiler.CallToken parent = f.profiler.beginCall(CHEST);
        f.clock.addAndGet(2_000_000);
        RenderProfiler.CallToken child = f.profiler.beginCall(COW);
        f.clock.addAndGet(3_000_000);
        RenderProfiler.CallToken grandchild = f.profiler.beginCall(grandchildContext);
        f.clock.addAndGet(5_000_000);
        f.profiler.endCall(grandchild, 12, 384, Set.of("translucent"), false);
        f.profiler.excludeNestedCall(child, grandchild);
        f.profiler.excludeNestedCall(child, grandchild); // Repeated exclusions must be harmless.
        f.clock.addAndGet(7_000_000);
        f.profiler.endCall(child, 8, 256, Set.of("solid"), true);
        f.profiler.excludeNestedCall(parent, child);
        f.profiler.excludeNestedCall(parent, child);
        f.profiler.excludeNestedCall(parent, parent);
        f.clock.addAndGet(11_000_000);
        f.profiler.endCall(parent, 4, 128, Set.of("solid"), false);
        f.finish(30_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(28, snapshot.performance().measuredRenderMs());
        assertEquals(2, snapshot.renderers().size());
        assertEquals(28, snapshot.renderers().stream().mapToDouble(row -> row.stats().averageMs()).sum());
        assertEquals(28, snapshot.renderers().stream().mapToDouble(row -> row.stats().cpuMs()).sum());
        Statistics vanillaRenderer = snapshot.renderers().stream().filter(row -> row.registryOwner().equals("minecraft"))
            .findFirst().orElseThrow().stats();
        Statistics customRenderer = snapshot.renderers().stream().filter(row -> row.registryOwner().equals("custom"))
            .findFirst().orElseThrow().stats();
        assertEquals(23, vanillaRenderer.averageMs());
        assertEquals(23, vanillaRenderer.cpuMs());
        assertEquals(5, customRenderer.averageMs());
        assertEquals(5, customRenderer.cpuMs());
        assertEquals(3, snapshot.performance().rendererCallsPerFrame());
        assertEquals(24, snapshot.performance().verticesPerFrame());
        assertEquals(13, objectStats(snapshot, "minecraft:chest").averageMs());
        assertEquals(10, objectStats(snapshot, "minecraft:cow").averageMs());
        assertEquals(1, objectStats(snapshot, "minecraft:cow").failures());
        assertEquals(5, objectStats(snapshot, "custom:grandchild").averageMs());
        assertEquals(13, objectStats(snapshot, "minecraft:chest").cpuMs());
        assertEquals(10, objectStats(snapshot, "minecraft:cow").cpuMs());
        assertEquals(5, objectStats(snapshot, "custom:grandchild").cpuMs());
    }

    @Test
    void staleAndUnrecordedChildrenCannotExcludeTimeFromANewFrameOrSession() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        RenderProfiler.CallToken finishedOld = f.profiler.beginCall(COW);
        f.clock.addAndGet(4_000_000);
        f.profiler.endCall(finishedOld, 0, 0, Set.of(), false);
        RenderProfiler.CallToken unfinishedOld = f.profiler.beginCall(COW);
        f.finish(10_000_000);
        f.profiler.beginFrame();
        RenderProfiler.CallToken parent = f.profiler.beginCall(CHEST);
        f.profiler.endCall(unfinishedOld, 0, 0, Set.of(), false);
        f.profiler.excludeNestedCall(parent, finishedOld);
        f.profiler.excludeNestedCall(parent, unfinishedOld);
        f.clock.addAndGet(2_000_000);
        f.profiler.endCall(parent, 0, 0, Set.of(), false);
        f.profiler.excludeNestedCall(parent, finishedOld);
        f.finish(10_000_000);
        assertEquals(2, objectStats(f.profiler.snapshot(), "minecraft:chest").peakMs());
        f.profiler.clear();
        f.profiler.beginFrame();
        parent = f.profiler.beginCall(CHEST);
        f.profiler.excludeNestedCall(parent, finishedOld);
        f.clock.addAndGet(3_000_000);
        f.profiler.endCall(parent, 0, 0, Set.of(), false);
        f.finish(10_000_000);
        assertEquals(3, objectStats(f.profiler.snapshot(), "minecraft:chest").peakMs());
    }

    private static Statistics objectStats(ProfilerSnapshot snapshot, String id) {
        return snapshot.objects().stream().filter(metric -> metric.id().equals(id)).findFirst().orElseThrow().stats();
    }

    @Test
    void droppedAdapterAssociationsAreVisibleWithoutFabricatingRendererSamples() {
        Fixture f = new Fixture();
        f.profiler.recordDroppedMeasurement(); // No frame: there was no measured scene.
        f.profiler.beginFrame();
        f.profiler.recordDroppedMeasurement();
        f.finish(10_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(1, snapshot.droppedMeasurements());
        assertEquals(0, snapshot.performance().rendererCallsPerFrame());
        assertTrue(snapshot.instances().isEmpty());
        assertTrue(snapshot.objects().isEmpty());
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(10_000_000_000L);
        final RenderProfiler profiler = new RenderProfiler(clock::get, clock::get, () -> 1_000_000L);

        void call(SampleContext context, long nanos, long vertices, long bytes, boolean failure) {
            RenderProfiler.CallToken token = profiler.beginCall(context);
            clock.addAndGet(nanos);
            profiler.endCall(token, vertices, bytes, Set.of("solid"), failure);
        }

        void finish(long frameNanos) {
            clock.addAndGet(frameNanos);
            profiler.endFrame(frameNanos);
        }
    }
}
