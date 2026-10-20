package dev.renderscope.core;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeferredContextsTest {
    @Test
    void distinctCallbacksUseIdentityEvenWhenTheirEqualsMethodsClaimTheyAreEqual() {
        DeferredContexts contexts = new DeferredContexts(2);
        Object first = new EqualTask();
        Object second = new EqualTask();
        assertTrue(first.equals(second));
        assertTrue(contexts.remember(first, context("0,64,0"), "translucent"));
        assertTrue(contexts.remember(second, context("1,64,0"), "solid"));
        assertEquals("0,64,0", contexts.find(first).context().key().identity());
        assertEquals("1,64,0", contexts.find(second).context().key().identity());
        assertEquals(2, contexts.size());
    }

    @Test
    void sameInstanceCanQueueSeveralActualRenderTypesButSharedTasksAreNotMisattributed() {
        DeferredContexts contexts = new DeferredContexts(2);
        Object task = new Object();
        assertTrue(contexts.remember(task, context("0,64,0"), "solid"));
        assertTrue(contexts.remember(task, context("0,64,0"), "translucent"));
        assertEquals(Set.of("solid", "translucent"), contexts.find(task).renderTypes());
        assertThrows(UnsupportedOperationException.class, () -> contexts.find(task).renderTypes().add("fake"));
        assertFalse(contexts.remember(task, context("1,64,0"), "solid"));
        assertNull(contexts.find(task), "A shared callback must not inherit the last object's position");
        assertFalse(contexts.remember(task, context("0,64,0"), "solid"));
    }

    @Test
    void capacityAndFrameResetPreventUnboundedRetentionAndCrossFrameAttribution() {
        DeferredContexts contexts = new DeferredContexts(1);
        Object first = new Object();
        Object second = new Object();
        assertTrue(contexts.remember(first, context("0,64,0"), null));
        assertFalse(contexts.remember(second, context("1,64,0"), "solid"));
        assertNull(contexts.find(second));
        assertEquals(1, contexts.size());
        contexts.clear();
        assertEquals(0, contexts.size());
        assertNull(contexts.find(first));
        assertTrue(contexts.remember(second, context("1,64,0"), "solid"));
        assertTrue(contexts.find(second).renderTypes().contains("solid"));
    }

    @Test
    void queuedWorkIsHeldThroughWeakReferencesAndCollectedAssociationsAreReaped() throws Exception {
        DeferredContexts contexts = new DeferredContexts(1);
        Object task = new Object();
        assertTrue(contexts.remember(task, context("0,64,0"), "translucent"));
        Field field = DeferredContexts.class.getDeclaredField("entries");
        field.setAccessible(true);
        Map<?, ?> retained = (Map<?, ?>) field.get(contexts);
        Object key = retained.keySet().iterator().next();
        assertInstanceOf(WeakReference.class, key);
        WeakReference<?> weak = (WeakReference<?>) key;
        assertSame(task, weak.get());
        // Exercise the exact ReferenceQueue cleanup deterministically without relying on a GC timer.
        weak.clear();
        assertTrue(weak.enqueue());
        assertEquals(0, contexts.size());
        assertNull(contexts.find(task));
    }

    private static SampleContext context(String identity) {
        return new SampleContext(new InstanceKey(ObjectKind.BLOCK_ENTITY, "minecraft:overworld", identity),
            "mekanism:basic_energy_cube", "mekanism", "mekanism.client.render.tileentity.RenderEnergyCube", "mekanism",
            new Position(0, 64, 0), new Bounds(0, 64, 0, 1, 65, 1));
    }

    private static final class EqualTask {
        @Override public boolean equals(Object other) { return other instanceof EqualTask; }
        @Override public int hashCode() { return 1; }
    }
}
