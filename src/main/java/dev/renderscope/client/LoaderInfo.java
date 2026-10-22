package dev.renderscope.client;

import dev.renderscope.client.platform.PlatformServices;

/** Runtime loader identity supplied by the selected platform adapter. */
public record LoaderInfo(String name, String version) {
    public static LoaderInfo current() {
        return PlatformServices.current().loader();
    }
}
