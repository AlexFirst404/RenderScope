package dev.renderscope.modern;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.gizmos.Gizmos;

final class ModernPlatform {
    private ModernPlatform() { }
    static InputConstants.Type keyboardType() { return InputConstants.Type.KEYSYM; }
    static Screen currentScreen(Minecraft client) { return client.screen; }
    static Gizmos.TemporaryCollection collectGizmos() { return Minecraft.getInstance().levelRenderer.collectPerFrameGizmos(); }
    static boolean hudHidden(Minecraft client) { return client.options.hideGui; }

}
