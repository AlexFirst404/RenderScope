package dev.renderscope.core;

import java.util.Objects;

/** An opaque value identity; never a reference to a game object. */
public record InstanceKey(ObjectKind kind, String dimension, String identity) {
    public InstanceKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(identity, "identity");
    }
}
