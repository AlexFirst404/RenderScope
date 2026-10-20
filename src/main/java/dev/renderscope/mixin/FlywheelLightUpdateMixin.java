package dev.renderscope.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.renderscope.client.integration.FlywheelHooks;
import dev.renderscope.core.MeasurementSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = {"dev.engine_room.flywheel.impl.visualization.storage.LightUpdatedVisualStorage$Updater$Simple",
    "dev.engine_room.flywheel.impl.visualization.storage.LightUpdatedVisualStorage$Updater$Synced"}, remap = false)
public abstract class FlywheelLightUpdateMixin {
    @WrapOperation(method = "updateLight", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/api/visual/LightUpdatedVisual;updateLight(F)V", remap = false), remap = false, require = 0)
    private void renderscope$light(@Coerce Object visual, float partialTick, Operation<Void> operation) {
        FlywheelHooks.measure(visual, MeasurementSource.FLYWHEEL_VISUAL_LIGHT, () -> operation.call(visual, partialTick));
    }
}
