package dev.renderscope.client.integration;

import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.lib.task.SimplyComposedPlan;
import dev.renderscope.client.RenderHooks;
import dev.renderscope.core.MeasurementSource;

import java.lang.ref.WeakReference;

/** Loaded only by Flywheel's optional Storage mixin; no Flywheel API leaks into the core. */
public final class FlywheelPlanAdapter {
    private FlywheelPlanAdapter() { }

    public static <C> Plan<C> wrap(Object visual, Plan<C> plan, MeasurementSource source) {
        return new MeasuredPlan<>(new WeakReference<>(visual), plan, source);
    }

    private record MeasuredPlan<C>(WeakReference<Object> visual, Plan<C> delegate, MeasurementSource source)
        implements SimplyComposedPlan<C> {
        @Override public void execute(TaskExecutor executor, C context, Runnable onCompletion) {
            FlywheelHooks.TaskContext task = FlywheelHooks.taskContext(visual.get(), source);
            if (task == null) { delegate.execute(executor, context, onCompletion); return; }
            TaskExecutor measured = new TaskExecutor() {
                @Override public int threadCount() { return executor.threadCount(); }
                @Override public void execute(Runnable command) {
                    executor.execute(() -> FlywheelHooks.measure(task, command));
                }
            };
            // Sum actual executing intervals, not scheduling-to-completion latency or idle waits.
            FlywheelHooks.measure(task, () -> delegate.execute(measured, context,
                () -> RenderHooks.withoutScope(onCompletion)));
        }
    }
}
