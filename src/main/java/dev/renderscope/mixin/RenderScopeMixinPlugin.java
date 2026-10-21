package dev.renderscope.mixin;

import dev.renderscope.client.integration.IntegrationVersions;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Gate optional descriptors before Mixin attempts to resolve their external API types. */
public final class RenderScopeMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope/Compatibility");
    private final Set<String> reported = new HashSet<>();
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        var versions = IntegrationVersions.installed();
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        boolean enabled;
        if (name.startsWith("Flywheel")) enabled = IntegrationVersions.supportsFlywheel(versions);
        else if (name.equals("MekanismDeferredRenderMixin")) enabled = IntegrationVersions.supportsMekanismDeferred(versions);
        else if (name.equals("FastBufferBuilderMixin")) enabled = IntegrationVersions.supportsFastVertices(versions);
        else if (name.equals("IrisBufferSourceMixin")) enabled = IntegrationVersions.supportsShaderBuffers(versions);
        else return true;
        if (reported.add(name)) LOGGER.info("Optional integration {}: {}", name, enabled ? "enabled for inspected ABI" : "disabled (absent or uninspected version)");
        return enabled;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
