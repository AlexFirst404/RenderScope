package dev.renderscope.modern.smoke;

final class NativeSmokePlatform {
    static net.minecraft.client.gui.screens.Screen currentScreen(net.minecraft.client.Minecraft client) { return client.gui.screen(); }
    static void moveCursor(net.minecraft.client.Minecraft client, double x, double y) {
        ((dev.renderscope.modern.smoke.mixin.NativeMouseInvoker)client.mouseHandler).renderscope$move(client.getWindow().handle(), x, y);
    }
    static com.mojang.blaze3d.pipeline.RenderTarget mainTarget(net.minecraft.client.Minecraft client) { return client.gameRenderer.mainRenderTarget(); }
}
