package dev.renderscope.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.renderscope.client.RenderHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {
    @Shadow private VertexFormat format;
    @Inject(method="endVertex",at=@At("RETURN"))
    private void renderscope$vertex(CallbackInfo ci) {
        if (RenderHooks.collectingGeometry()) RenderHooks.vertex(format.getVertexSize());
    }
}
