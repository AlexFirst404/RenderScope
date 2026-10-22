package dev.renderscope.modern.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.renderscope.modern.ModernHooks;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityDispatcherMixin {
    @WrapOperation(method = "tryExtractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;extractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private void renderscope$extract(BlockEntityRenderer<?, ?> renderer, BlockEntity entity, BlockEntityRenderState state,
                                     float delta, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay breaking, Operation<Void> original) {
        var scope = ModernHooks.beginBlockEntity(entity, renderer);
        boolean failed = true;
        try {
            original.call(renderer, entity, state, delta, camera, breaking);
            ModernHooks.associate(state, scope);
            failed = false;
        } finally { ModernHooks.end(scope, failed); }
    }
    @WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void renderscope$submit(BlockEntityRenderer<?, ?> renderer, BlockEntityRenderState state, PoseStack pose,
                                    SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
        var scope = ModernHooks.beginSubmit(state, renderer);
        boolean failed = true;
        try { original.call(renderer, state, pose, collector, camera); failed = false; }
        finally { ModernHooks.end(scope, failed); }
    }
}
