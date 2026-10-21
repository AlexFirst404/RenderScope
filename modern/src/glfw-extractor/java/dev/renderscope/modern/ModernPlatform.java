package dev.renderscope.modern;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;

final class ModernPlatform {
    private ModernPlatform() { }
    static InputConstants.Type keyboardType() { return InputConstants.Type.KEYSYM; }
    static net.minecraft.client.gui.screens.Screen currentScreen(Minecraft client) { return client.gui.screen(); }
    static Gizmos.TemporaryCollection collectGizmos() { return Minecraft.getInstance().levelExtractor.collectPerFrameMainThreadGizmos(); }
}
