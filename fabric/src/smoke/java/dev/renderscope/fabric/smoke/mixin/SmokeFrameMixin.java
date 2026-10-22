package dev.renderscope.fabric.smoke.mixin;

import dev.renderscope.fabric.smoke.FabricSmokeTest;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SmokeFrameMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void renderscope$readRenderedFrame(float partialTick, long started, boolean renderLevel, CallbackInfo ci) {
        FabricSmokeTest.frame();
    }
}
