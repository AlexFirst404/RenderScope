package dev.renderscope.modern;

import dev.renderscope.client.ModOwnership;

/** Ownership is metadata, not an assumption that a registry owner wrote all of a renderer. */
public final class ModCatalog {
    private static final ModOwnership OWNERSHIP = new ModOwnership();
    private ModCatalog() { }
    public static String displayName(String id) {
        return ModOwnership.displayName(id);
    }
    public static String implementationOwner(Class<?> implementation) {
        return OWNERSHIP.rendererOwner(implementation);
    }
}
