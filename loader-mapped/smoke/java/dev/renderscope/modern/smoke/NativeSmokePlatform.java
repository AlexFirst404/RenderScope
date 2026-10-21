package dev.renderscope.modern.smoke;

final class NativeSmokePlatform {
    private NativeSmokePlatform() { }
    static net.minecraft.client.gui.screens.Screen currentScreen(net.minecraft.client.Minecraft client) {
        return client.screen;
    }
}
