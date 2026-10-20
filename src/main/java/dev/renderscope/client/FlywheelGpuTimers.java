package dev.renderscope.client;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayDeque;
import java.util.Deque;

/** Asynchronous timestamps around shared Flywheel draws. Never assigns batch GPU time to objects. */
public final class FlywheelGpuTimers {
    private static final int RING_SIZE = 64;
    private static final long WINDOW_NS = 5_000_000_000L;
    private static final Slot[] RING = new Slot[RING_SIZE];
    private static final Deque<Frame> FRAMES = new ArrayDeque<>();
    private static boolean enabled;
    private static boolean checked;
    private static boolean supported;
    private static boolean failed;
    private static int next;
    private static long generation;
    private static long dropped;
    private static long completed;
    private static Frame frame;
    private static Snapshot cached = new Snapshot("disabled", 0, 0, 0, 0, 0, 0);
    private static long cachedAt;

    static { for (int i = 0; i < RING.length; i++) RING[i] = new Slot(); }
    private FlywheelGpuTimers() { }

    public record Snapshot(String status, int sampledFrames, double averageMs, double peakMs,
                           long completedBatches, int pendingBatches, long droppedBatches) {
        public boolean available() { return status.equals("available"); }
    }

    public static final class Token {
        private final Slot slot;
        private final long generation;
        private Token(Slot slot) { this.slot = slot; this.generation = FlywheelGpuTimers.generation; }
    }

    /** Called on the render thread before the world's Flywheel draw stages. Full mode only. */
    public static void frameStarted(boolean collect) {
        if (!collect) {
            if (enabled) reset();
            enabled = false;
            return;
        }
        enabled = true;
        if (failed) return;
        try {
            pollReady();
            long now = System.nanoTime();
            frame = new Frame(now);
            FRAMES.addLast(frame);
            while (!FRAMES.isEmpty() && (FRAMES.size() > 4096 || now - FRAMES.peekFirst().started > WINDOW_NS))
                FRAMES.removeFirst();
            if (now - cachedAt >= 250_000_000L) refresh(now);
        } catch (RuntimeException exception) { fail(); }
    }

    public static Token begin() {
        if (!enabled || failed || frame == null) return null;
        try {
            if (!checked) {
                checked = true;
                var caps = GL.getCapabilities();
                supported = caps.OpenGL33 || caps.GL_ARB_timer_query;
            }
            if (!supported) return null;
            Slot slot = RING[next];
            if (slot.pending || slot.open) { dropped++; frame.invalid = true; return null; }
            next = (next + 1) % RING.length;
            if (slot.startQuery == 0) {
                slot.startQuery = GL15C.glGenQueries();
                slot.endQuery = GL15C.glGenQueries();
            }
            slot.frame = frame;
            slot.open = true;
            frame.expected++;
            GL33C.glQueryCounter(slot.startQuery, GL33C.GL_TIMESTAMP);
            return new Token(slot);
        } catch (RuntimeException exception) { fail(); return null; }
    }

    public static void end(Token token) {
        if (token == null || token.generation != generation || !token.slot.open || failed) return;
        try {
            GL33C.glQueryCounter(token.slot.endQuery, GL33C.GL_TIMESTAMP);
            token.slot.open = false;
            token.slot.pending = true;
        } catch (RuntimeException exception) { fail(); }
    }

    /** Only reads results whose end timestamp is already available. There is no wait or glFinish. */
    private static void pollReady() {
        if (!supported) return;
        for (Slot slot : RING) {
            if (!slot.pending || GL15C.glGetQueryObjecti(slot.endQuery, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long start = GL33C.glGetQueryObjectui64(slot.startQuery, GL15C.GL_QUERY_RESULT);
            long end = GL33C.glGetQueryObjectui64(slot.endQuery, GL15C.GL_QUERY_RESULT);
            if (end >= start) {
                slot.frame.gpuNanos += end - start;
                slot.frame.finished++;
                completed++;
            } else { slot.frame.invalid = true; dropped++; }
            slot.pending = false;
            slot.frame = null;
        }
    }

    public static Snapshot snapshot() {
        long now = System.nanoTime();
        if (now - cachedAt >= 250_000_000L) refresh(now);
        return cached;
    }

    private static void refresh(long now) {
        int count = 0, pending = 0;
        long total = 0, peak = 0;
        for (Slot slot : RING) if (slot.pending || slot.open) pending++;
        for (Frame sample : FRAMES) {
            if (sample == frame || now - sample.started > WINDOW_NS || sample.expected == 0
                    || sample.expected != sample.finished || sample.invalid) continue;
            count++;
            total += sample.gpuNanos;
            peak = Math.max(peak, sample.gpuNanos);
        }
        String status = !enabled ? "disabled" : failed ? "failed" : checked && !supported ? "unsupported"
                : count == 0 ? "waiting" : "available";
        cached = new Snapshot(status, count, count == 0 ? 0 : total / (double) count / 1e6,
                peak / 1e6, completed, pending, dropped);
        cachedAt = now;
    }

    private static void fail() {
        failed = true;
        refresh(System.nanoTime());
    }

    /** Called on disconnect, world replacement or measurement disable; no game objects are retained. */
    public static void reset() {
        generation++;
        for (Slot slot : RING) {
            try {
                if (slot.startQuery != 0) GL15C.glDeleteQueries(slot.startQuery);
                if (slot.endQuery != 0) GL15C.glDeleteQueries(slot.endQuery);
            } catch (RuntimeException ignored) { /* A destroyed GL context owns its own resource cleanup. */ }
            slot.startQuery = slot.endQuery = 0;
            slot.pending = slot.open = false;
            slot.frame = null;
        }
        FRAMES.clear();
        frame = null;
        enabled = checked = supported = failed = false;
        next = 0;
        dropped = completed = 0;
        cachedAt = 0;
        cached = new Snapshot("disabled", 0, 0, 0, 0, 0, 0);
    }

    private static final class Slot {
        int startQuery, endQuery;
        boolean pending, open;
        Frame frame;
    }

    private static final class Frame {
        final long started;
        int expected, finished;
        long gpuNanos;
        boolean invalid;
        Frame(long started) { this.started = started; }
    }
}
