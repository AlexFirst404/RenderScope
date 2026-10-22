package dev.renderscope.client;

import dev.renderscope.core.RenderProfiler;
import dev.renderscope.core.SampleContext;
import dev.renderscope.core.WeakIdentityMap;

/** Bounded identity associations for pooled render states; values retain no game objects. */
public final class PreparedContexts {
    private final WeakIdentityMap<Prepared> states;
    public PreparedContexts() { this(8192); }
    public PreparedContexts(int capacity) { states = new WeakIdentityMap<>(capacity); }
    public boolean put(Object state, SampleContext context, RenderProfiler.FrameHandle frame) {
        return state != null && context != null && frame != null && states.put(state, new Prepared(context, frame));
    }
    public Prepared get(Object state) { return states.get(state); }
    public void clear() { states.clear(); }
    public record Prepared(SampleContext context, RenderProfiler.FrameHandle frame) { }
}
