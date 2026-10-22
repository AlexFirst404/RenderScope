package dev.renderscope.mixin;

import dev.renderscope.client.ClientRuntime;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge removed the general HUD event in 1.20.5; render after the native layered HUD. */
@Mixin(Gui.class)
public abstract class ForgeGuiMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void renderscope$hud(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        ClientRuntime runtime = ClientRuntime.current();
        if (runtime != null) runtime.overlay().renderHud(graphics);
    }
}
