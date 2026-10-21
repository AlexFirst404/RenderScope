package dev.renderscope.client.platform;

import java.util.Objects;

public final class PlatformServices {
    private static PlatformAccess platform;
    private PlatformServices() { }
    public static void install(PlatformAccess access) { platform = Objects.requireNonNull(access); }
    public static PlatformAccess current() {
        if (platform == null) throw new IllegalStateException("RenderScope loader bootstrap has not initialized");
        return platform;
    }
}
