package dev.renderscope.core;

import java.util.Objects;

public record SampleContext(InstanceKey key, String registryId, String registryOwner,
                            String rendererClass, String rendererOwner,
                            Position position, Bounds bounds) {
    public SampleContext {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(registryId, "registryId");
        // A coordinate can contain multiple registry types during one capture. Keep their
        // history separate even when an adapter still uses the compatible three-argument key.
        key = key.withRegistry(registryId);
        Objects.requireNonNull(registryOwner, "registryOwner");
        Objects.requireNonNull(rendererClass, "rendererClass");
        Objects.requireNonNull(rendererOwner, "rendererOwner");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(bounds, "bounds");
    }
}
