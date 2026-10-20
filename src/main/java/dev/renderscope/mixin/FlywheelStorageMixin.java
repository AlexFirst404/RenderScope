package dev.renderscope.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.ForEachPlan;
import dev.engine_room.flywheel.lib.task.functional.ConsumerWithContext;
import dev.renderscope.client.integration.FlywheelHooks;
import dev.renderscope.client.integration.FlywheelPlanAdapter;
import dev.renderscope.core.MeasurementSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.BiConsumer;

/** Exact Flywheel 1.0.5 Storage boundaries, with no hard runtime dependency. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.storage.Storage", remap = false)
public abstract class FlywheelStorageMixin {
    @Shadow(remap = false) @Final private Map<Object, Visual> visuals;

    @WrapOperation(method = {"add", "lambda$recreateAll$4"}, at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/impl/visualization/storage/Storage;createRaw(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Ljava/lang/Object;F)Ldev/engine_room/flywheel/api/visual/Visual;",
        remap = false), remap = false, require = 0)
    private Visual renderscope$create(@Coerce Object storage, VisualizationContext context, Object original,
                                      float partialTick, Operation<Visual> operation) {
        FlywheelHooks.VisualCall call = FlywheelHooks.beginCreation(original, storage);
        boolean failed = true;
        Visual visual;
        try {
            visual = operation.call(storage, context, original, partialTick);
            failed = false;
        } finally { FlywheelHooks.end(call, failed); }
        FlywheelHooks.associate(visual, original);
        return visual;
    }

    @WrapOperation(method = {"remove", "lambda$recreateAll$4"}, at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/api/visual/Visual;delete()V", remap = false), remap = false, require = 0)
    private void renderscope$delete(Visual visual, Operation<Void> operation) {
        FlywheelHooks.forget(visual);
        operation.call(visual);
    }

    @Inject(method = "invalidate", at = @At("HEAD"), remap = false, require = 0)
    private void renderscope$invalidate(CallbackInfo ci) { visuals.values().forEach(FlywheelHooks::forget); }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/api/visual/Visual;update(F)V", remap = false), remap = false, require = 0)
    private void renderscope$update(Visual visual, float partialTick, Operation<Void> operation) {
        FlywheelHooks.measure(visual, MeasurementSource.FLYWHEEL_VISUAL_UPDATE, () -> operation.call(visual, partialTick));
    }

    @WrapOperation(method = "setup", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/api/visual/LightUpdatedVisual;updateLight(F)V", remap = false), remap = false, require = 0)
    private void renderscope$initialLight(@Coerce Object visual, float partialTick, Operation<Void> operation) {
        FlywheelHooks.measure(visual, MeasurementSource.FLYWHEEL_VISUAL_LIGHT, () -> operation.call(visual, partialTick));
    }

    @WrapOperation(method = "setup", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/api/visual/DynamicVisual;planFrame()Ldev/engine_room/flywheel/api/task/Plan;", remap = false),
        remap = false, require = 0)
    private Plan<?> renderscope$dynamicPlan(DynamicVisual visual, Operation<Plan<?>> operation) {
        return FlywheelPlanAdapter.wrap(visual, operation.call(visual), MeasurementSource.FLYWHEEL_VISUAL_FRAME);
    }

    @WrapOperation(method = "setup", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/api/visual/TickableVisual;planTick()Ldev/engine_room/flywheel/api/task/Plan;", remap = false),
        remap = false, require = 0)
    private Plan<?> renderscope$tickPlan(TickableVisual visual, Operation<Plan<?>> operation) {
        return FlywheelPlanAdapter.wrap(visual, operation.call(visual), MeasurementSource.FLYWHEEL_VISUAL_TICK);
    }

    @WrapOperation(method = "framePlan", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/lib/task/ForEachPlan;of(Ldev/engine_room/flywheel/lib/task/functional/SupplierWithContext$Ignored;Ldev/engine_room/flywheel/lib/task/functional/ConsumerWithContext;)Ldev/engine_room/flywheel/lib/task/ForEachPlan;",
        remap = false), remap = false, require = 0)
    private ForEachPlan<?, ?> renderscope$simpleFrame(@Coerce Object supplier, @Coerce Object action,
                                                       Operation<ForEachPlan<?, ?>> operation) {
        ConsumerWithContext<Object, Object> measured = (visual, context) -> FlywheelHooks.measure(visual,
            MeasurementSource.FLYWHEEL_VISUAL_FRAME, () -> ((BiConsumer<Object, Object>) action).accept(visual, context));
        return operation.call(supplier, measured);
    }

    @WrapOperation(method = "tickPlan", at = @At(value = "INVOKE", target =
        "Ldev/engine_room/flywheel/lib/task/ForEachPlan;of(Ldev/engine_room/flywheel/lib/task/functional/SupplierWithContext$Ignored;Ldev/engine_room/flywheel/lib/task/functional/ConsumerWithContext;)Ldev/engine_room/flywheel/lib/task/ForEachPlan;",
        remap = false), remap = false, require = 0)
    private ForEachPlan<?, ?> renderscope$simpleTick(@Coerce Object supplier, @Coerce Object action,
                                                      Operation<ForEachPlan<?, ?>> operation) {
        ConsumerWithContext<Object, Object> measured = (visual, context) -> FlywheelHooks.measure(visual,
            MeasurementSource.FLYWHEEL_VISUAL_TICK, () -> ((BiConsumer<Object, Object>) action).accept(visual, context));
        return operation.call(supplier, measured);
    }
}
