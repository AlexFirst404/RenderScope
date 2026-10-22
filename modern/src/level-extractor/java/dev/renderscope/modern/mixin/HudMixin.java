package dev.renderscope.modern.mixin;

import dev.renderscope.modern.ModernOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void renderscope$hud(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) { ModernOverlay.extractHud(graphics); }
}
