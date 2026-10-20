package dev.renderscope.core;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Propagates a value captured when a task is queued, including an explicit null value.
 * Null means unmeasured work, rather than permission to acquire a later frame on execution.
 * Payloads should be profiler identities or immutable values, never game objects.
 */
public final class ScheduledContext<T> {
    private final ThreadLocal<Captured<T>> executing = new ThreadLocal<>();
    private final Supplier<T> outsideTask;

    public ScheduledContext(Supplier<T> outsideTask) { this.outsideTask = Objects.requireNonNull(outsideTask); }

    public T current() {
        Captured<T> captured = executing.get();
        return captured == null ? outsideTask.get() : captured.value;
    }

    public Runnable wrap(Runnable command) {
        Objects.requireNonNull(command);
        Captured<T> captured = new Captured<>(current());
        return () -> {
            Captured<T> previous = executing.get();
            executing.set(captured);
            try { command.run(); }
            finally {
                if (previous == null) executing.remove(); else executing.set(previous);
            }
        };
    }

    public void clear() { executing.remove(); }

    private record Captured<T>(T value) { }
}
