package dev.renderscope.modern;

import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.client.platform.NeoForgePlatform;
import dev.renderscope.client.platform.PlatformServices;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

final class NeoForgeModernBootstrap {
    private NeoForgeModernBootstrap() { }
    static void initialize(IEventBus modBus) {
        PlatformServices.install(new NeoForgePlatform());
        ModernHooks.applySettings();
        KeyMapping.Category category = new KeyMapping.Category(Identifier.fromNamespaceAndPath("renderscope", "profile"));
        KeyMapping open = new KeyMapping("key.renderscope.open", ModernPlatform.keyboardType(), InputConstants.KEY_F8, category);
        modBus.addListener((RegisterKeyMappingsEvent event) -> { event.registerCategory(category); event.register(open); });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Minecraft client = Minecraft.getInstance();
            while (open.consumeClick()) client.setScreenAndShow(new RenderScopeScreen(ModernPlatform.currentScreen(client)));
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            if (event.getScreen() instanceof PauseScreen screen) event.addListener(Button.builder(Component.literal("RenderScope"),
                button -> Minecraft.getInstance().setScreenAndShow(new RenderScopeScreen(screen)))
                .bounds(Math.max(4, screen.width - 112), 6, 106, 20).build());
        });
    }
}
