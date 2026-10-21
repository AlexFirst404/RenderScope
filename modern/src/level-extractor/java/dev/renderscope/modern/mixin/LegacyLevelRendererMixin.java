package dev.renderscope.modern.mixin;

import dev.renderscope.modern.ModernHooks;
import dev.renderscope.modern.ModernOverlay;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.1 keeps world extraction inside LevelRenderer rather than the later LevelExtractor class. */
@Mixin(LevelRenderer.class)
abstract class LegacyLevelRendererMixin {
    @Inject(method = {"extractLevel", "renderLevel"}, at = @At("HEAD"))
    private void renderscope$worldStart(CallbackInfo ci) { ModernHooks.enterWorld(); }
    @Inject(method = {"extractLevel", "renderLevel"}, at = @At("RETURN"))
    private void renderscope$worldEnd(CallbackInfo ci) { ModernHooks.leaveWorld(); }
    @Inject(method = "extractLevel", at = @At("TAIL"))
    private void renderscope$worldGizmos(CallbackInfo ci) { ModernOverlay.emitWorldGizmos(); }
}
