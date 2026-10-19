package dev.renderscope.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.renderscope.client.RenderHooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiBufferSource.BufferSource.class)
public abstract class BufferSourceMixin {
    @Inject(method="getBuffer",at=@At("HEAD"))
    private void renderscope$type(RenderType type, CallbackInfoReturnable<VertexConsumer> cir) {
        if (RenderHooks.collectingGeometry()) RenderHooks.renderType(((RenderTypeNameAccessor) type).renderscope$name());
    }
}
