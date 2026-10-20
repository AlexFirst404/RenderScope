package dev.renderscope.core;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.*;

class WeakIdentityMapTest {
    @Test void usesIdentityAndReplacesAnExistingEntryAtCapacity() {
        WeakIdentityMap<String> map = new WeakIdentityMap<>(2);
        Object first = new String("equal");
        Object second = new String("equal");
        assertTrue(map.put(first, "first"));
        assertTrue(map.put(second, "second"));
        assertEquals("first", map.get(first));
        assertEquals("second", map.get(second));
        assertTrue(map.put(first, "replacement"));
        assertFalse(map.put(new Object(), "too many"));
        assertEquals("replacement", map.remove(first));
        assertNull(map.get(first));
        assertTrue(map.put(new Object(), "now allowed"));
        map.clear();
        assertEquals(0, map.size());
    }

    @Test void doesNotRetainADeletedVisualAndReapsItsValue() throws Exception {
        WeakIdentityMap<String> map = new WeakIdentityMap<>(1);
        Object visual = new Object();
        WeakReference<Object> reference = new WeakReference<>(visual);
        map.put(visual, "immutable metadata");
        visual = null;
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            System.gc(); Thread.sleep(10);
        }
        assertNull(reference.get(), "Profiler associations must not retain visuals");
        assertEquals(0, map.size());
        assertTrue(map.values().isEmpty());
    }
}
