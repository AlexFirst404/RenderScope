package dev.renderscope.modern.mixin;

import dev.renderscope.modern.ModernHooks;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "render", at = @At("HEAD")) private void renderscope$worldStart(CallbackInfo ci) { ModernHooks.enterWorld(); }
    @Inject(method = "render", at = @At("RETURN")) private void renderscope$worldEnd(CallbackInfo ci) { ModernHooks.leaveWorld(); }
}
