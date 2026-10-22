package dev.renderscope.modern.smoke;

/** Separate QA mod, never included in a production RenderScope JAR. */
public final class FabricInstalledSmoke implements net.fabricmc.api.ClientModInitializer {
    @Override public void onInitializeClient() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> NativeSmokeContext.tick());
    }
}
