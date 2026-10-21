package dev.renderscope.modern;

import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.modern.draw.Gizmos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

final class ModernPlatform {
    private ModernPlatform() { }
    static InputConstants.Type keyboardType() { return InputConstants.Type.KEYSYM; }
    static Screen currentScreen(Minecraft client) { return client.screen; }
    static Gizmos.TemporaryCollection collectGizmos() { return Gizmos.collect(); }
}
