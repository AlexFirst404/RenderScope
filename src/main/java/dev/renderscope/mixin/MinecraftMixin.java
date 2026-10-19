package dev.renderscope.mixin;

import dev.renderscope.client.RenderHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method="runTick",at=@At("HEAD"))
    private void renderscope$beginFrame(boolean renderLevel, CallbackInfo ci) { RenderHooks.beginFrame(); }
    @Inject(method="runTick",at=@At("RETURN"))
    private void renderscope$endFrame(boolean renderLevel, CallbackInfo ci) { RenderHooks.endFrame(); }
}
