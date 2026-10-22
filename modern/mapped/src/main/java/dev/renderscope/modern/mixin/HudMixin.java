package dev.renderscope.modern.mixin;

import dev.renderscope.modern.ModernOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class HudMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void renderscope$hud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) { ModernOverlay.extractHud(graphics); }
}
