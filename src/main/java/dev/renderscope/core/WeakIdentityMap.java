package dev.renderscope.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded associations whose keys compare by identity and never keep game objects alive. */
public final class WeakIdentityMap<V> {
    private final int limit;
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<Key, V> values = new HashMap<>();

    public WeakIdentityMap(int limit) {
        if (limit < 1 || limit > 65536) throw new IllegalArgumentException("Invalid weak map limit");
        this.limit = limit;
    }
    public synchronized boolean put(Object key, V value) {
        Objects.requireNonNull(key); Objects.requireNonNull(value); reap();
        Key probe = new Key(key, null);
        if (!values.containsKey(probe) && values.size() >= limit) return false;
        values.put(new Key(key, collected), value);
        return true;
    }
    public synchronized V get(Object key) {
        reap(); return key == null ? null : values.get(new Key(key, null));
    }
    public synchronized V remove(Object key) {
        reap(); return key == null ? null : values.remove(new Key(key, null));
    }
    public synchronized List<V> values() { reap(); return List.copyOf(values.values()); }
    public synchronized int size() { reap(); return values.size(); }
    public synchronized void clear() { values.clear(); reap(); }
    private void reap() {
        Key key;
        while ((key = (Key) collected.poll()) != null) values.remove(key);
    }
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object key, ReferenceQueue<Object> queue) { super(key, queue); hash = System.identityHashCode(key); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object value = get();
            return value != null && other instanceof Key key && value == key.get();
        }
    }
}
