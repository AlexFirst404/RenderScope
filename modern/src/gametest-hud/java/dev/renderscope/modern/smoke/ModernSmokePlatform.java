package dev.renderscope.modern.smoke;

/** Exact screen API for the 26.2/26.3 QA. */
public final class ModernSmokePlatform {
    private ModernSmokePlatform() { }
    public static net.minecraft.client.gui.screens.Screen currentScreen(net.minecraft.client.Minecraft client) {
        return client.gui.screen();
    }
}
