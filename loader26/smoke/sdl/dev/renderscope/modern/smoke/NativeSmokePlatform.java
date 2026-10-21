package dev.renderscope.modern.smoke;

final class NativeSmokePlatform {
    static net.minecraft.client.gui.screens.Screen currentScreen(net.minecraft.client.Minecraft client) { return client.gui.screen(); }
    static void moveCursor(net.minecraft.client.Minecraft client, double x, double y) {
        client.mouseHandler.onMove(client.getWindow().handle(), x, y, 0, 0);
    }
    static com.mojang.blaze3d.pipeline.RenderTarget mainTarget(net.minecraft.client.Minecraft client) {
        return client.gameRenderer.mainRenderTarget();
    }
}
