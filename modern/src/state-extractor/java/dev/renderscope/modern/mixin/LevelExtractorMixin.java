package dev.renderscope.modern.mixin;

import dev.renderscope.modern.ModernHooks;
import dev.renderscope.modern.ModernOverlay;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
    @Inject(method = "extract", at = @At("HEAD")) private void renderscope$worldStart(CallbackInfo ci) { ModernHooks.enterWorld(); }
    @Inject(method = "extract", at = @At("RETURN")) private void renderscope$worldEnd(CallbackInfo ci) { ModernHooks.leaveWorld(); }
    @Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractGizmos()V"))
    private void renderscope$worldOverlay(CallbackInfo ci) { ModernOverlay.emitWorldGizmos(); }
}
