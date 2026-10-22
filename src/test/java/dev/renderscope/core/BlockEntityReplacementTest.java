package dev.renderscope.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Registry replacement must not transfer the previous occupant's CPU work to the live block. */
public class BlockEntityReplacementTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String POSITION = "0,64,0";

    @Test void sampleContextQualifiesLegacyBlockKeysAndLiveLookupUsesTheSameIdentity() {
        InstanceKey raw = new InstanceKey(ObjectKind.BLOCK_ENTITY, DIMENSION, POSITION);
        SampleContext old = context("old_mod", raw), replacement = context("new_mod", raw);
        assertNotEquals(old.key(), replacement.key());
        assertEquals(POSITION, replacement.key().identity(), "Opaque position encoding remains compatible");
        assertEquals("new_mod:machine", replacement.key().registryId());
        InstanceKey liveInspectorKey = raw.withRegistry("new_mod:machine");
        assertEquals(liveInspectorKey, replacement.key());
        assertNotEquals(liveInspectorKey, old.key(), "An inspector cannot select the previous registry type");
        assertSame(replacement.key(), replacement.key().withRegistry("new_mod:machine"));
        InstanceKey entity = new InstanceKey(ObjectKind.ENTITY, DIMENSION, "42#opaque-uuid");
        assertSame(entity, context("new_mod", entity).key(), "Entity ID/UUID resolution is unchanged");
        assertEquals("", entity.registryId());
    }

    @Test void rollingInstancesAndDiagnosticDrilldownKeepEachActualOwnersCosts() {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) { f.profiler.beginFrame(); f.call("old_mod", 8_000_000); f.finish(50_000_000); }
        for (int i = 0; i < 20; i++) { f.profiler.beginFrame(); f.call("new_mod", 1_000_000); f.finish(50_000_000); }
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(2, snapshot.instances().size());
        assertEquals(4, owner(snapshot.instances(), "old_mod").stats().averageMs());
        assertEquals(0.5, owner(snapshot.instances(), "new_mod").stats().averageMs());
        assertEquals(20, owner(snapshot.instances(), "new_mod").stats().calls());
        DiagnosticReport report = DiagnosticEngine.analyze(snapshot);
        assertTrue(report.ready());
        assertEquals("old_mod", report.topMod().orElseThrow().mod().id());
        assertEquals("old_mod:machine", report.worstObject().orElseThrow().id());
        assertEquals("old_mod:machine", report.worstInstance().orElseThrow().context().registryId());
        assertEquals(4, report.worstInstance().orElseThrow().stats().averageMs());
    }

    @Test void capturePreservesReplacedHistoryWhileRollingExpiryAndZeroFramesRemainCorrect() {
        CaptureReport capture = captureWithReplacement();
        assertEquals(3, capture.frames());
        assertEquals(2, capture.instances().size());
        assertEquals(8.0 / 3, owner(capture.instances(), "old_mod").stats().averageMs());
        assertEquals(1.0 / 3, owner(capture.instances(), "new_mod").stats().averageMs());
        assertEquals(8, owner(capture.instances(), "old_mod").stats().peakMs());
        assertEquals(1, owner(capture.instances(), "new_mod").stats().peakMs());
        assertEquals(1, owner(capture.instances(), "old_mod").stats().calls());
        assertEquals(1, owner(capture.instances(), "new_mod").stats().calls());
        assertEquals(owner(capture.instances(), "old_mod").context().position(),
            owner(capture.instances(), "new_mod").context().position());
    }

    @Test void differentTypesAtOnePositionInTheSameFrameKeepTheirObjectAndModGroups() {
        Fixture f = new Fixture(); f.profiler.beginFrame();
        f.call("old_mod", 8_000_000); f.call("new_mod", 1_000_000); f.finish(50_000_000);
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(2, snapshot.instances().size());
        assertEquals(2, snapshot.mods().size());
        assertEquals(2, snapshot.objects().size());
        assertEquals(9, snapshot.performance().measuredRenderMs());
        assertEquals(8, owner(snapshot.instances(), "old_mod").stats().averageMs());
        assertEquals(1, owner(snapshot.instances(), "new_mod").stats().averageMs());
        for (AggregateMetric object : snapshot.objects()) {
            assertEquals(object.registryOwner() + ":machine", object.id());
            assertEquals(object.registryOwner().equals("old_mod") ? 8 : 1, object.stats().averageMs());
        }
    }

    /** Also exercises both report allowlists with real captured rows, without serializing instance keys. */
    public static CaptureReport captureWithReplacement() {
        Fixture f = new Fixture(); f.profiler.setSampleWindowSeconds(1); f.profiler.startCapture(0);
        f.profiler.beginFrame(); f.call("old_mod", 8_000_000); f.finish(50_000_000);
        f.clock.addAndGet(600_000_000);
        f.profiler.beginFrame(); f.call("new_mod", 1_000_000); f.finish(50_000_000);
        f.clock.addAndGet(450_000_000);
        List<InstanceMetric> live = f.profiler.snapshot().instances();
        assertEquals(1, live.size(), "The old occupant expired independently from the rolling window");
        assertEquals("new_mod:machine", live.get(0).context().registryId());
        assertEquals(1, live.get(0).stats().averageMs());
        f.clock.addAndGet(1_100_000_000);
        f.profiler.beginFrame(); f.finish(50_000_000);
        assertTrue(f.profiler.snapshot().instances().isEmpty());
        return f.profiler.stopCapture();
    }

    private static SampleContext context(String owner, InstanceKey key) {
        return new SampleContext(key, owner + ":machine", owner, owner + ".Renderer", owner,
            new Position(0,64,0), new Bounds(0,64,0,1,65,1));
    }
    private static InstanceMetric owner(List<InstanceMetric> rows, String owner) {
        return rows.stream().filter(row -> row.context().registryOwner().equals(owner)).findFirst().orElseThrow();
    }
    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(10_000_000_000L);
        final RenderProfiler profiler = new RenderProfiler(clock::get, () -> -1, () -> 0);
        void call(String owner, long duration) {
            if (profiler.currentFrame() == null) throw new AssertionError("Missing real frame");
            var token = profiler.beginCall(context(owner, new InstanceKey(ObjectKind.BLOCK_ENTITY, DIMENSION, POSITION)));
            clock.addAndGet(duration); profiler.endCall(token, 0, 0, Set.of(), false);
        }
        void finish(long duration) {
            clock.addAndGet(duration); profiler.endFrame(duration);
        }
    }
}
