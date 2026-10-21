package dev.renderscope.modern.smoke.mixin;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.MouseHandler.class)
public interface NativeMouseInvoker {
    @org.spongepowered.asm.mixin.gen.Invoker("onMove")
    void renderscope$move(long window, double x, double y);
}
