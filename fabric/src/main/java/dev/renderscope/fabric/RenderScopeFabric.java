package dev.renderscope.fabric;

import dev.renderscope.client.ClientRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

public final class RenderScopeFabric implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientRuntime runtime = ClientRuntime.initialize(new FabricPlatform());
        KeyBindingHelper.registerKeyBinding(ClientRuntime.openKey());
        ClientTickEvents.END_CLIENT_TICK.register(client -> runtime.clientTick());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            runtime.pauseMenu(screen, screen.children(), button -> Screens.getButtons(screen).add(button));
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, key, scan, modifiers) ->
                    !runtime.screenKey(current, key, scan));
        });
        WorldRenderEvents.START.register(context -> runtime.beginWorldPass());
        // LAST supplies the camera view and survives renderers that replace the cloud/translucent path.
        WorldRenderEvents.LAST.register(context -> runtime.overlay().render(context.matrixStack(),
                context.projectionMatrix(), context.camera().getPosition(), context.frustum(), context.tickDelta()));
        WorldRenderEvents.END.register(context -> runtime.endWorldPass());
        HudRenderCallback.EVENT.register((graphics, partialTick) -> runtime.overlay().renderHud(graphics));
    }
}
