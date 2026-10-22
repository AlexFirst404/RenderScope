package dev.renderscope.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.renderscope.client.integration.ShaderBufferMetrics;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iris/Oculus replace BufferSource.getBuffer rather than calling its vanilla implementation. */
@Pseudo
@Mixin(targets = "net.irisshaders.batchedentityrendering.impl.SegmentedBufferBuilder", remap = false)
public abstract class IrisBufferSourceMixin {
    // Both names are from the inspected production binaries; the handler descriptor is remapped normally.
    @Inject(method = {"getBuffer", "m_6299_"}, at = @At("HEAD"), remap = false, require = 1)
    private void renderscope$shaderType(RenderType type, CallbackInfoReturnable<VertexConsumer> ci) {
        ShaderBufferMetrics.requested(((RenderTypeNameAccessor) type).renderscope$name());
    }
}
