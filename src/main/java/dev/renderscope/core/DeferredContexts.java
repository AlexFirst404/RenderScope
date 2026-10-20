package dev.renderscope.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded weak-identity association for work queued by a measured renderer and executed later
 * in the same frame. Values contain only immutable profiler data, never the queued game task.
 */
public final class DeferredContexts {
    private static final int MAX_RENDER_TYPES = 32;
    private final int maximumEntries;
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<IdentityReference, Entry> entries = new HashMap<>();
    private static final Entry AMBIGUOUS = new Entry(null, Set.of());

    public DeferredContexts(int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > 65536) {
            throw new IllegalArgumentException("Deferred context limit must be between 1 and 65536");
        }
        this.maximumEntries = maximumEntries;
    }

    /** False means capacity was reached or a reused task cannot be assigned to one instance. */
    public synchronized boolean remember(Object task, SampleContext context, String renderType) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(context, "context");
        reap();
        IdentityReference probe = new IdentityReference(task, null);
        Entry previous = entries.get(probe);
        if (previous == AMBIGUOUS) return false;
        if (previous != null && (!previous.context().key().equals(context.key())
            || !previous.context().registryId().equals(context.registryId())
            || !previous.context().rendererClass().equals(context.rendererClass()))) {
            // A shared callback queued by different objects has no honest single-object owner.
            entries.put(probe, AMBIGUOUS);
            return false;
        }
        if (previous == null && entries.size() >= maximumEntries) return false;
        Set<String> types = previous == null ? new HashSet<>() : new HashSet<>(previous.renderTypes());
        if (renderType != null && !renderType.isEmpty() && types.size() < MAX_RENDER_TYPES) {
            types.add(renderType.length() > 128 ? renderType.substring(0, 128) : renderType);
        }
        entries.put(previous == null ? new IdentityReference(task, collected) : probe, new Entry(context, types));
        return true;
    }

    public synchronized Entry find(Object task) {
        if (task == null) return null;
        reap();
        Entry entry = entries.get(new IdentityReference(task, null));
        return entry == AMBIGUOUS ? null : entry;
    }

    public synchronized int size() { reap(); return entries.size(); }

    /** Frame/session boundary: no context is carried into the next scene. */
    public synchronized void clear() { entries.clear(); reap(); }

    private void reap() {
        IdentityReference reference;
        while ((reference = (IdentityReference) collected.poll()) != null) entries.remove(reference);
    }

    public record Entry(SampleContext context, Set<String> renderTypes) {
        public Entry { renderTypes = Set.copyOf(renderTypes); }
    }

    private static final class IdentityReference extends WeakReference<Object> {
        private final int identityHash;

        IdentityReference(Object object, ReferenceQueue<Object> queue) {
            super(object, queue);
            identityHash = System.identityHashCode(object);
        }

        @Override public int hashCode() { return identityHash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityReference reference)) return false;
            Object object = get();
            return object != null && object == reference.get();
        }
    }
}
