package dev.renderscope.modern.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.renderscope.modern.ModernHooks;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityDispatcherMixin {
    @WrapOperation(method = "extractEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState renderscope$extract(EntityRenderer<?, ?> renderer, Entity entity, float delta,
                                                  Operation<EntityRenderState> original) {
        var scope = ModernHooks.beginEntity(entity, renderer);
        boolean failed = true;
        try {
            EntityRenderState state = original.call(renderer, entity, delta);
            ModernHooks.associate(state, scope);
            failed = false;
            return state;
        } finally { ModernHooks.end(scope, failed); }
    }
    @WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void renderscope$submit(EntityRenderer<?, ?> renderer, EntityRenderState state, PoseStack pose,
                                    SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
        var scope = ModernHooks.beginSubmit(state, renderer);
        boolean failed = true;
        try { original.call(renderer, state, pose, collector, camera); failed = false; }
        finally { ModernHooks.end(scope, failed); }
    }
}
