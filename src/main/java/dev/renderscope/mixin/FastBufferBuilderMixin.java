package dev.renderscope.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.renderscope.client.integration.FastVertexMetrics;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe successful native memory-copy paths after Sodium's higher-priority mixins merge them. */
@Mixin(value = BufferBuilder.class, priority = 800)
public abstract class FastBufferBuilderMixin {
    @Shadow private VertexFormat format;

    @Inject(method = "push(Lorg/lwjgl/system/MemoryStack;JILnet/caffeinemc/mods/sodium/api/vertex/format/VertexFormatDescription;)V",
            at = @At("RETURN"), remap = false, require = 0)
    private void renderscope$batch(MemoryStack stack, long source, int count, @Coerce Object sourceFormat, CallbackInfo ci) {
        FastVertexMetrics.batch(count, format.getVertexSize());
    }

    @Inject(method = "sodium$moveToNextVertex", at = @At("RETURN"), remap = false, require = 0)
    private void renderscope$delegateVertex(CallbackInfo ci) {
        FastVertexMetrics.delegateVertex(format.getVertexSize());
    }
}
