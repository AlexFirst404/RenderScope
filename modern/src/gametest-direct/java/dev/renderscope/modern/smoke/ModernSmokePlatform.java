package dev.renderscope.modern.smoke;

/** Exact screen API for the 26.1 family and mapped 1.21.9–1.21.11 QA. */
public final class ModernSmokePlatform {
    private ModernSmokePlatform() { }
    public static net.minecraft.client.gui.screens.Screen currentScreen(net.minecraft.client.Minecraft client) {
        return client.screen;
    }
}
