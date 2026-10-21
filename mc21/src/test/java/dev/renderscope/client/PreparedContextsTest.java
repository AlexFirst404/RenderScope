package dev.renderscope.client;

import dev.renderscope.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedContextsTest {
    private SampleContext context(String identity) {
        return new SampleContext(new InstanceKey(ObjectKind.ENTITY, "minecraft:overworld", identity),
                "minecraft:pig", "minecraft", "PigRenderer", "minecraft", new Position(1,2,3), new Bounds(0,0,0,1,1,1));
    }
    @Test void equalButDistinctStatesDoNotConfuseInstancesAndPooledStateUsesLatestValues() {
        var profiler = new RenderProfiler();
        profiler.beginFrame();
        var frame = profiler.currentFrame();
        var contexts = new PreparedContexts();
        Object first = new String("same"), second = new String("same");
        assertTrue(contexts.put(first, context("1"), frame));
        assertTrue(contexts.put(second, context("2"), frame));
        assertEquals("1", contexts.get(first).context().key().identity());
        assertEquals("2", contexts.get(second).context().key().identity());
        assertTrue(contexts.put(first, context("3"), frame));
        assertEquals("3", contexts.get(first).context().key().identity());
        assertSame(frame, contexts.get(first).frame());
    }
    @Test void nullSchedulingCannotAttachToLaterFrameAndSessionClearDropsAssociations() {
        var profiler = new RenderProfiler();
        var contexts = new PreparedContexts();
        Object state = new Object();
        assertFalse(contexts.put(state, context("1"), null));
        profiler.beginFrame();
        var originalFrame = profiler.currentFrame();
        assertTrue(contexts.put(state, context("1"), originalFrame));
        profiler.endFrame(1_000_000);
        profiler.beginFrame();
        assertSame(originalFrame, contexts.get(state).frame());
        assertNull(profiler.beginCall(contexts.get(state).context(), MeasurementSource.RENDERER, contexts.get(state).frame()));
        contexts.clear();
        assertNull(contexts.get(state));
    }
}
