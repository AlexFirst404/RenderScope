package dev.renderscope.mixin;

import dev.renderscope.client.integration.FlywheelHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Pin the frame at scheduling time and propagate it through Flywheel worker task trees. */
@Pseudo
@Mixin(targets = {"dev.engine_room.flywheel.impl.task.ParallelTaskExecutor",
    "dev.engine_room.flywheel.impl.task.SerialTaskExecutor"}, remap = false)
public abstract class FlywheelTaskExecutorMixin {
    @ModifyVariable(method = "execute(Ljava/lang/Runnable;)V", at = @At("HEAD"),
        argsOnly = true, remap = false, require = 0)
    private Runnable renderscope$pinFrame(Runnable task) { return FlywheelHooks.wrapTask(task); }
}
