package dev.renderscope.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.renderscope.client.RenderHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both ordinary and packed 1.21.1 addVertex paths allocate through this single method. */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {
    @Shadow @Final private VertexFormat format;

    @Inject(method="beginVertex", at=@At("RETURN"))
    private void renderscope$vertex(CallbackInfoReturnable<Long> cir) {
        if (RenderHooks.collectingGeometry()) RenderHooks.vertex(format.getVertexSize());
    }
}
