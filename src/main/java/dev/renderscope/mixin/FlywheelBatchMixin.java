package dev.renderscope.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.renderscope.client.integration.FlywheelHooks;
import dev.renderscope.client.RenderHooks;
import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Measure submission after Flywheel joins visual tasks, rather than counting the join as work. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class FlywheelBatchMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/api/backend/Engine;render(Ldev/engine_room/flywheel/api/backend/RenderContext;)V", remap = false),
        remap = false, require = 0)
    private void renderscope$submit(@Coerce Object engine, RenderContext context, Operation<Void> operation) {
        if (!RenderHooks.isWorldPass() || context.level() != Minecraft.getInstance().level) {
            operation.call(engine, context);
            return;
        }
        FlywheelHooks.BatchCall call = FlywheelHooks.beginBatch();
        boolean failed = true;
        try { operation.call(engine, context); failed = false; }
        finally { FlywheelHooks.endBatch(call, failed); }
    }
}
