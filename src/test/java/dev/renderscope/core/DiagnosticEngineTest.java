package dev.renderscope.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static dev.renderscope.core.DiagnosticReport.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticEngineTest {
    @Test void realProfilerRankingUsesMeasuredModWorkAndKeepsSharedWorkOutsideItsShare() {
        Fixture f = new Fixture();
        SampleContext alpha = context("alpha", "machine", ObjectKind.BLOCK_ENTITY, "one");
        SampleContext beta = context("beta", "mob", ObjectKind.ENTITY, "two");
        for (int i = 0; i < 40; i++) {
            f.profiler.beginFrame();
            f.call(alpha, 3);
            f.call(beta, 1);
            var phase = f.profiler.beginPhase("flywheel:batch_submit", MeasurementSource.FLYWHEEL_BATCH_SUBMIT);
            f.clock.addAndGet(12_000_000);
            f.profiler.endPhase(phase, false);
            f.finish();
        }
        DiagnosticReport report = DiagnosticEngine.analyze(f.profiler.snapshot());
        assertTrue(report.ready());
        assertEquals("alpha", report.topMod().orElseThrow().mod().id());
        assertEquals(0.75, report.topMod().orElseThrow().measuredCpuShare());
        assertEquals(4, report.measuredCpuMs());
        assertEquals(4, report.attributedCpuMs());
        assertEquals(12, report.sharedCpuMs());
        assertEquals(0.08, report.measuredToFrameRatio());
        assertTrue(report.reasons().contains(Reason.SHARED_WORK_SEPARATE));
        assertEquals(2, report.rankedMods().size(), "A shared engine batch must not become a synthetic mod");
    }

    @Test void drilldownStaysInsideLeaderAndMatchesObjectKindEvenWhenRegistryIdsCollide() {
        Fixture f = new Fixture();
        SampleContext a = context("alpha", "machine", ObjectKind.BLOCK_ENTITY, "a");
        SampleContext b = context("alpha", "machine", ObjectKind.BLOCK_ENTITY, "b");
        SampleContext sameIdEntity = context("alpha", "machine", ObjectKind.ENTITY, "entity");
        SampleContext globalWorst = context("beta", "other", ObjectKind.BLOCK_ENTITY, "costly");
        for (int i = 0; i < 40; i++) {
            f.profiler.beginFrame();
            f.call(a, 4); f.call(b, 4); f.call(sameIdEntity, 5); f.call(globalWorst, 12);
            f.finish();
        }
        DiagnosticReport report = DiagnosticEngine.analyze(f.profiler.snapshot());
        assertEquals("alpha", report.topMod().orElseThrow().mod().id());
        AggregateMetric object = report.worstObject().orElseThrow();
        assertEquals("alpha:machine", object.id());
        assertEquals(ObjectKind.BLOCK_ENTITY, object.kind());
        assertEquals(8, object.stats().averageMs());
        InstanceMetric instance = report.worstInstance().orElseThrow();
        assertEquals("alpha", instance.context().registryOwner());
        assertEquals(ObjectKind.BLOCK_ENTITY, instance.context().key().kind());
        assertEquals(4, instance.stats().averageMs(), "Neither the global worst nor same-id entity belongs to this drilldown");
    }

    @Test void readinessUsesCollectedFramesCallsAndDurationInsteadOfConfiguredFiveSecondWindow() {
        Fixture f = new Fixture();
        SampleContext context = context("alpha", "machine", ObjectKind.BLOCK_ENTITY, "a");
        f.profiler.beginFrame(); f.call(context, 2); f.finish();
        DiagnosticReport first = DiagnosticEngine.analyze(f.profiler.snapshot());
        assertEquals(Readiness.COLLECTING, first.readiness());
        assertEquals(0.05, first.observedSeconds());
        assertEquals(1, first.frames());
        assertEquals(1, first.measuredCalls());
        assertFalse(first.ready());
        assertTrue(first.reasons().contains(Reason.INSUFFICIENT_DATA));
        for (int i = 1; i < 20; i++) {
            f.profiler.beginFrame(); f.call(context, 2); f.finish();
        }
        f.clock.addAndGet(250_000_000); // The profiler's immutable view refreshes at 4 Hz.
        DiagnosticReport ready = DiagnosticEngine.analyze(f.profiler.snapshot());
        assertEquals(Readiness.READY, ready.readiness());
        assertEquals(1, ready.observedSeconds());
        assertEquals(20, ready.minimumFrames());
        assertEquals(20, ready.minimumCalls());
        assertEquals(1, ready.minimumSeconds());
    }

    @Test void manyFramesButOneInvocationRemainPreliminary() {
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(100, 20, 0.01,
            List.of(mod("alpha", stats(0.01, 1))), List.of(), List.of(), List.of(), List.of(), 0));
        assertEquals(Readiness.COLLECTING, report.readiness());
        assertFalse(report.ready());
        assertTrue(report.topMod().isPresent(), "Real but sparse samples can be shown as preliminary");
    }

    @Test void oneSecondRollingWindowCanBecomeReadyDespiteFrameBoundaryGaps() {
        ProfilerSnapshot original = snapshot(60, 16.5, 1,
            List.of(mod("alpha", stats(1, 60))), List.of(), List.of(), List.of(), List.of(), 0);
        ProfilerSnapshot shortWindow = new ProfilerSnapshot(original.enabled(), 1, original.performance(),
            original.mods(), original.renderers(), original.objects(), original.instances(), original.timeline(),
            original.capture(), original.droppedMeasurements(), original.phases());
        DiagnosticReport report = DiagnosticEngine.analyze(shortWindow);
        assertEquals(0.99, report.observedSeconds());
        assertEquals(0.8, report.minimumSeconds());
        assertTrue(report.ready(), "A fully populated short rolling window need not accumulate exactly 1.000s");
        assertEquals(1, DiagnosticEngine.analyze(original).minimumSeconds(), "Longer windows keep the usual minimum");
    }

    @Test void sharedPhaseOnlyDoesNotInventAnObjectOffender() {
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(100, 20, 0,
            List.of(), List.of(), List.of(), List.of(), List.of(new PhaseMetric("flywheel:batch_submit", stats(8, 100))), 0));
        assertEquals(Readiness.NO_SAMPLES, report.readiness());
        assertEquals(8, report.sharedCpuMs());
        assertTrue(report.topMod().isEmpty());
        assertTrue(report.worstObject().isEmpty());
        assertTrue(report.worstInstance().isEmpty());
        assertTrue(report.reasons().contains(Reason.SHARED_WORK_SEPARATE));
    }

    @Test void parallelVisualIntervalsCanExceedAFrameWithoutBecomingGpuTimeOrFpsSavings() {
        Statistics visual = new Statistics(12, 14, 20, 8, 1, 100, 0, 0, 0, 1, Set.of(),
            Map.of(MeasurementSource.FLYWHEEL_VISUAL_FRAME, new SourceMetric(12, 8, 1, 100, 0)));
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(100, 10, 20,
            List.of(mod("alpha", visual), mod("beta", stats(8, 100))), List.of(), List.of(), List.of(), List.of(), 0), 30);
        assertEquals(0.6, report.topMod().orElseThrow().measuredCpuShare());
        assertEquals(2, report.measuredToFrameRatio(), "Raw task sum must not be clamped to a false coverage percentage");
        assertEquals(1000.0 / 30, report.targetFrameBudgetMs());
        assertEquals(0.36, report.topMod().orElseThrow().targetBudgetRatio(), 0.0000001);
        assertTrue(report.parallelSources());
        assertTrue(report.reasons().contains(Reason.INTERVAL_SUM_EXCEEDS_FRAME));
        assertTrue(report.reasons().containsAll(Set.of(Reason.CPU_NOT_GPU, Reason.NO_CAUSAL_FPS_PREDICTION, Reason.LIMITED_SCOPE)));
    }

    @Test void smallObservedSliceWarnsWithoutClaimingAllRenderingWasCovered() {
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(100, 50, 1,
            List.of(mod("alpha", stats(1, 100))), List.of(), List.of(), List.of(), List.of(), 0));
        assertTrue(report.coverageLimited());
        assertTrue(report.reasons().contains(Reason.LOW_MEASURED_SLICE));
        assertEquals(1, report.topMod().orElseThrow().measuredCpuShare(), "100% of a small measured slice is not 100% of rendering");
        assertEquals(0.02, report.measuredToFrameRatio());
    }

    @Test void spikeEvidenceUsesOnlyRetainedRecentSpikesAndDoesNotCountOldThirtySecondHistory() {
        List<FramePoint> timeline = List.of(
            point(20, 200, true, Map.of("alpha", 100.0)),
            point(26, 70, true, Map.of("beta", 8.0)),
            point(28, 80, true, Map.of("alpha", 3.0)),
            point(30, 20, false, Map.of("alpha", 1.0)));
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(100, 20, 1,
            List.of(mod("alpha", stats(1, 100))), List.of(), List.of(), timeline, List.of(), 0));
        SpikeEvidence evidence = report.spikes();
        assertEquals(2, evidence.retainedFrames());
        assertEquals(1, evidence.leaderListedFrames());
        assertEquals(80, evidence.largestFrameMs());
        assertEquals(3, evidence.leaderPeakMs());
        assertEquals(28_000_000_000L, evidence.worstFrame().orElseThrow().timestampNanos());
        assertEquals(SpikeRelevance.LEADER_LISTED, evidence.relevance());
    }

    @Test void unlistedSpikeOwnerIsReportedAsUnlistedNotAsAbsenceOrExoneration() {
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(100, 20, 1,
            List.of(mod("alpha", stats(1, 100))), List.of(), List.of(),
            List.of(point(30, 80, true, Map.of("beta", 8.0))), List.of(), 0));
        assertEquals(SpikeRelevance.LEADER_NOT_LISTED, report.spikes().relevance());
        assertEquals(0, report.spikes().leaderListedFrames());
        assertTrue(report.reasons().contains(Reason.NO_CAUSAL_FPS_PREDICTION));
    }

    @Test void clearedOrEmptyRollingDataCannotExposeAnOldTimelineOffender() {
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(0, 0, 0, List.of(), List.of(), List.of(),
            List.of(point(30, 80, true, Map.of("alpha", 8.0))), List.of(), 0));
        assertEquals(Readiness.NO_SAMPLES, report.readiness());
        assertTrue(report.topMod().isEmpty());
        assertEquals(0, report.spikes().retainedFrames());
        assertTrue(report.spikes().worstFrame().isEmpty());
    }

    @Test void disabledStateAndSessionClearDoNotRetainAnActionablePriorWorld() {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) {
            f.profiler.beginFrame(); f.call(context("alpha", "block", ObjectKind.BLOCK_ENTITY, "a"), 1); f.finish();
        }
        assertTrue(DiagnosticEngine.analyze(f.profiler.snapshot()).ready());
        f.profiler.setEnabled(false);
        DiagnosticReport disabled = DiagnosticEngine.analyze(f.profiler.snapshot());
        assertEquals(Readiness.DISABLED, disabled.readiness());
        assertFalse(disabled.ready());
        assertTrue(disabled.reasons().contains(Reason.PROFILING_DISABLED));
        f.profiler.clear();
        f.profiler.setEnabled(true);
        DiagnosticReport empty = DiagnosticEngine.analyze(f.profiler.snapshot());
        assertEquals(Readiness.NO_SAMPLES, empty.readiness());
        assertTrue(empty.worstInstance().isEmpty());
    }

    @Test void registryOwnerAndImplementationOwnerRemainDistinctEvidence() {
        Fixture f = new Fixture();
        SampleContext registry = context("minecraft", "chest", ObjectKind.BLOCK_ENTITY, "a");
        SampleContext overridden = new SampleContext(registry.key(), registry.registryId(), registry.registryOwner(),
            "optimization.ChestRenderer", "optimization", registry.position(), registry.bounds());
        for (int i = 0; i < 20; i++) {
            f.profiler.beginFrame(); f.call(overridden, 1); f.finish();
        }
        DiagnosticReport report = DiagnosticEngine.analyze(f.profiler.snapshot());
        assertEquals("minecraft", report.topMod().orElseThrow().mod().id());
        assertTrue(report.reasons().contains(Reason.REGISTRY_RENDERER_OWNER_DIFFERS));
        assertEquals("optimization", report.worstInstance().orElseThrow().context().rendererOwner());
    }

    @Test void droppedOrMissingInstanceDataIsNotReplacedWithAnUnrelatedWorldObject() {
        InstanceMetric unrelated = new InstanceMetric(context("beta", "block", ObjectKind.BLOCK_ENTITY, "b"), stats(4, 100), 1);
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot(100, 20, 8,
            List.of(mod("alpha", stats(8, 100))), List.of(), List.of(unrelated), List.of(), List.of(), 7));
        assertTrue(report.measurementsDropped());
        assertTrue(report.worstObject().isEmpty());
        assertTrue(report.worstInstance().isEmpty());
    }

    @Test void returnedCollectionsAreImmutableAndReferenceBudgetMustBeValid() {
        ProfilerSnapshot snapshot = snapshot(100, 20, 1,
            List.of(mod("alpha", stats(1, 100))), List.of(), List.of(), List.of(), List.of(), 0);
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot);
        assertThrows(UnsupportedOperationException.class, () -> report.rankedMods().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.reasons().clear());
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> DiagnosticEngine.analyze(snapshot, invalid));
        }
    }

    private static SampleContext context(String owner, String id, ObjectKind kind, String identity) {
        return new SampleContext(new InstanceKey(kind, "minecraft:overworld", identity), owner + ':' + id, owner,
            owner + ".Renderer", owner, new Position(1, 64, 1), new Bounds(1,64,1,2,65,2));
    }
    private static Statistics stats(double ms, long calls) {
        return new Statistics(ms, ms, ms, ms, calls / 100.0, calls, 0, 0, 0, 1, Set.of());
    }
    private static AggregateMetric mod(String id, Statistics stats) {
        return new AggregateMetric(id, null, id, id + ".Renderer", id, stats);
    }
    private static FramePoint point(long seconds, double ms, boolean spike, Map<String, Double> owners) {
        return new FramePoint(seconds * 1_000_000_000L, ms, 8, 0, 8, spike, owners, null);
    }
    private static ProfilerSnapshot snapshot(int frames, double frameMs, double measuredMs,
                                             List<AggregateMetric> mods, List<AggregateMetric> objects,
                                             List<InstanceMetric> instances, List<FramePoint> timeline,
                                             List<PhaseMetric> phases, long dropped) {
        Performance p = new Performance(frames, frameMs > 0 ? 1000 / frameMs : 0, frameMs, frameMs,
            0, frameMs, frameMs, frameMs, frameMs, measuredMs, 0, measuredMs, 1, 0, 1, instances.size(), 0, 0);
        return new ProfilerSnapshot(true, 5, p, mods, List.of(), objects, instances, timeline,
            new CaptureStatus(false, 0, 0), dropped, phases);
    }
    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(10_000_000_000L);
        final RenderProfiler profiler = new RenderProfiler(clock::get, clock::get, () -> 0L);
        void call(SampleContext context, long millis) {
            var token = profiler.beginCall(context);
            clock.addAndGet(millis * 1_000_000);
            profiler.endCall(token, 0, 0, Set.of(), false);
        }
        void finish() { clock.addAndGet(50_000_000); profiler.endFrame(50_000_000); }
    }
}
