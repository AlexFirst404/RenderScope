package dev.renderscope.modern;

import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.client.platform.PlatformServices;
import dev.renderscope.fabric.FabricPlatform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class RenderScopeModern implements ClientModInitializer {
    @Override public void onInitializeClient() {
        PlatformServices.install(new FabricPlatform());
        ModernHooks.applySettings();
        KeyMapping open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.renderscope.open",
            ModernPlatform.keyboardType(), InputConstants.KEY_F8,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("renderscope", "profile"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.consumeClick()) client.setScreenAndShow(new RenderScopeScreen(ModernPlatform.currentScreen(client)));
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen) Screens.getWidgets(screen).add(Button.builder(Component.literal("RenderScope"),
                ignored -> client.setScreenAndShow(new RenderScopeScreen(screen)))
                .bounds(Math.max(4, width - 112), 6, 106, 20).build());
        });
    }
}
