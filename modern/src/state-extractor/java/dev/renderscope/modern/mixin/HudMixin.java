package dev.renderscope.modern.mixin;

import dev.renderscope.modern.ModernOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void renderscope$hud(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) { ModernOverlay.extractHud(graphics); }
}
