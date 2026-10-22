package dev.renderscope.core;

import java.util.Objects;

/** An opaque value identity; never a reference to a game object. */
public record InstanceKey(ObjectKind kind, String dimension, String identity, String registryId) {
    public InstanceKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(registryId, "registryId");
    }

    /** Legacy callers supply spatial identity; SampleContext qualifies block entities by registry type. */
    public InstanceKey(ObjectKind kind, String dimension, String identity) {
        this(kind, dimension, identity, "");
    }

    /** Preserve the opaque identity format while separating different block types at the same position. */
    public InstanceKey withRegistry(String registryId) {
        Objects.requireNonNull(registryId, "registryId");
        return kind != ObjectKind.BLOCK_ENTITY || this.registryId.equals(registryId) ? this
            : new InstanceKey(kind, dimension, identity, registryId);
    }
}
