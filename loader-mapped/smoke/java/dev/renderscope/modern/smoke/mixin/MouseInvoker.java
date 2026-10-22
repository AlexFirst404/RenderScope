package dev.renderscope.modern.smoke.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** QA-only native input callback; never packaged with RenderScope itself. */
@Mixin(MouseHandler.class)
public interface MouseInvoker {
    @Invoker(value="onMove",remap=false) void renderscope$move(long window, double x, double y);
}
