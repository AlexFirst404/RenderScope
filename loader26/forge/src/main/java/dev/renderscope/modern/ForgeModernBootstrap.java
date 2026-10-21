package dev.renderscope.modern;

import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.client.platform.ForgePlatform;
import dev.renderscope.client.platform.PlatformServices;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

final class ForgeModernBootstrap {
    private ForgeModernBootstrap() { }
    static void initialize() {
        PlatformServices.install(new ForgePlatform());
        ModernHooks.applySettings();
        KeyMapping open = new KeyMapping("key.renderscope.open", ModernPlatform.keyboardType(), InputConstants.KEY_F8,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("renderscope", "profile")));
        RegisterKeyMappingsEvent.BUS.addListener(event -> event.register(open));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            Minecraft client = Minecraft.getInstance();
            while (open.consumeClick()) client.setScreenAndShow(new RenderScopeScreen(ModernPlatform.currentScreen(client)));
        });
        ScreenEvent.Init.Post.BUS.addListener(event -> {
            if (event.getScreen() instanceof PauseScreen screen) event.addListener(Button.builder(Component.literal("RenderScope"),
                button -> Minecraft.getInstance().setScreenAndShow(new RenderScopeScreen(screen)))
                .bounds(Math.max(4, screen.width - 112), 6, 106, 20).build());
        });
    }
}
