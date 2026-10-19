package dev.renderscope.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.renderscope.client.RenderHooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @WrapOperation(method="setupAndRender", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
    private static <E extends BlockEntity> void renderscope$measure(BlockEntityRenderer<E> renderer, E entity,
            float partialTick, PoseStack poses, MultiBufferSource buffers, int light, int overlay, Operation<Void> original) {
        RenderHooks.Scope scope = RenderHooks.begin(entity, renderer);
        boolean failed = true;
        try {
            original.call(renderer, entity, partialTick, poses, buffers, light, overlay);
            failed = false;
        } finally { RenderHooks.end(scope, failed); }
    }
}
