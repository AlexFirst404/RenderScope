package dev.renderscope.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Also supplies an actual captured aggregation to both report serializers' tests. */
public class RendererOwnershipTest {
    public static final String IMPLEMENTATION = "shared.api.GenericBlockRenderer";

    @Test void sharedImplementationKeepsIndependentOwnerRowsAndModDetailLookup() {
        Fixture f = new Fixture();
        f.profiler.beginFrame();
        f.call("mod_a", 1_000_000, MeasurementSource.RENDER_EXTRACTION, false);
        f.call("mod_b", 2_000_000, MeasurementSource.RENDER_SUBMISSION, true);
        f.finish();
        ProfilerSnapshot snapshot = f.profiler.snapshot();
        assertEquals(2, snapshot.renderers().size());
        assertTrue(snapshot.renderers().stream().allMatch(row -> row.id().equals(IMPLEMENTATION)
            && row.rendererClass().equals(IMPLEMENTATION) && row.rendererOwner().equals("shared_api")));
        AggregateMetric a = owner(snapshot.renderers(), "mod_a"), b = owner(snapshot.renderers(), "mod_b");
        assertEquals(1, a.stats().averageMs());
        assertEquals(2, b.stats().averageMs());
        assertEquals(0, a.stats().failures());
        assertEquals(1, b.stats().failures());
        assertEquals(Set.of(MeasurementSource.RENDER_EXTRACTION), a.stats().sources().keySet());
        assertEquals(Set.of(MeasurementSource.RENDER_SUBMISSION), b.stats().sources().keySet());
        assertEquals(1, a.stats().instances());
        assertEquals(1, b.stats().instances());
        assertEquals(3, snapshot.performance().measuredRenderMs());
        assertEquals(1, owner(snapshot.mods(), "mod_a").stats().averageMs());
        assertEquals(2, owner(snapshot.mods(), "mod_b").stats().averageMs());
    }

    @Test void rollingExpiryRemovesOnlyTheExpiredOwnersRendererAndIncludesZeroFrames() {
        Fixture f = new Fixture();
        f.profiler.setSampleWindowSeconds(1);
        f.profiler.beginFrame(); f.call("mod_a", 1_000_000, MeasurementSource.RENDERER, false); f.finish();
        f.clock.addAndGet(600_000_000);
        f.profiler.beginFrame(); f.call("mod_b", 2_000_000, MeasurementSource.RENDERER, false); f.finish();
        assertEquals(0.5, owner(f.profiler.snapshot().renderers(), "mod_a").stats().averageMs());
        assertEquals(1, owner(f.profiler.snapshot().renderers(), "mod_b").stats().averageMs());
        f.clock.addAndGet(500_000_000);
        List<AggregateMetric> rows = f.profiler.snapshot().renderers();
        assertEquals(1, rows.size());
        assertEquals(2, owner(rows, "mod_b").stats().averageMs());
        assertEquals(1, rows.get(0).stats().instances());
    }

    @Test void capturePreservesBothOwnersAfterRollingExpiryAndIncludesEmptyFrames() {
        CaptureReport capture = captureWithSharedRenderer();
        assertEquals(3, capture.frames());
        assertEquals(2, capture.renderers().size());
        assertTrue(capture.renderers().stream().allMatch(row -> row.id().equals(IMPLEMENTATION)));
        AggregateMetric a = owner(capture.renderers(), "mod_a"), b = owner(capture.renderers(), "mod_b");
        assertEquals(1.0 / 3, a.stats().averageMs());
        assertEquals(2.0 / 3, b.stats().averageMs());
        assertEquals(1, a.stats().calls());
        assertEquals(1, b.stats().calls());
        assertEquals(1, a.stats().peakMs());
        assertEquals(2, b.stats().peakMs());
        assertEquals(1, a.stats().p95Ms(), 0.03);
        assertEquals(2, b.stats().p95Ms(), 0.05);
    }

    public static CaptureReport captureWithSharedRenderer() {
        Fixture f = new Fixture(); f.profiler.setSampleWindowSeconds(1); f.profiler.startCapture(0);
        f.profiler.beginFrame(); f.call("mod_a", 1_000_000, MeasurementSource.RENDER_EXTRACTION, false); f.finish();
        f.clock.addAndGet(600_000_000);
        f.profiler.beginFrame(); f.call("mod_b", 2_000_000, MeasurementSource.RENDER_SUBMISSION, true); f.finish();
        f.clock.addAndGet(1_100_000_000);
        f.profiler.beginFrame(); f.finish();
        assertTrue(f.profiler.snapshot().renderers().isEmpty(), "Capture must retain work already expired from the live window");
        return f.profiler.stopCapture();
    }

    private static AggregateMetric owner(List<AggregateMetric> rows, String owner) {
        AggregateMetric metric = rows.stream().filter(row -> row.registryOwner().equals(owner)).findFirst().orElseThrow();
        if (metric.id().equals(IMPLEMENTATION)) {
            assertEquals(IMPLEMENTATION, metric.rendererClass());
            assertEquals("shared_api", metric.rendererOwner());
        }
        return metric;
    }
    private static final class Fixture {
        final AtomicLong clock = new AtomicLong();
        final RenderProfiler profiler = new RenderProfiler(clock::get, () -> -1, () -> 0);
        long frameEnd;
        void call(String owner, long duration, MeasurementSource source, boolean failed) {
            int x = owner.equals("mod_a") ? 0 : 1;
            SampleContext context = new SampleContext(new InstanceKey(ObjectKind.BLOCK_ENTITY, "minecraft:overworld", x + ",64,0"),
                owner + ":fixture", owner, IMPLEMENTATION, "shared_api", new Position(x,64,0), new Bounds(x,64,0,x+1,65,1));
            RenderProfiler.CallToken token = profiler.beginCall(context, source, profiler.currentFrame());
            clock.addAndGet(duration); profiler.endCall(token, 0, 0, Set.of(), failed);
        }
        void finish() { clock.set(Math.max(clock.get(), frameEnd + 16_000_000)); frameEnd = clock.get(); profiler.endFrame(16_000_000); }
    }
}
