package dev.renderscope.modern.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.renderscope.modern.ModernHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @WrapMethod(method = "runTick")
    private void renderscope$frame(boolean renderLevel, Operation<Void> original) {
        ModernHooks.beginFrame();
        try { original.call(renderLevel); } finally { ModernHooks.endFrame(); }
    }
}
