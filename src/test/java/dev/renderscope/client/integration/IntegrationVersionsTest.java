package dev.renderscope.client.integration;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationVersionsTest {
    @Test void rubidiumCompatibilitySodiumIdCannotDisableTheRealInspectedFork() {
        assertTrue(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "rubidium", "0.7.1", "sodium", "0.5.2")));
        assertFalse(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "rubidium", "0.8.0", "sodium", "0.5.13+mc1.20.1")));
        assertTrue(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "embeddium", "0.3.31+mc1.20.1", "rubidium", "0.7.1", "sodium", "0.6.0")));
    }
    @Test void missingOrOlderFlywheelCannotLoadOnePointZeroDescriptors() {
        assertFalse(IntegrationVersions.supportsFlywheel(Map.of()));
        assertFalse(IntegrationVersions.supportsFlywheel(Map.of("minecraft", "1.20.1", "flywheel", "0.6.10-7")));
        assertFalse(IntegrationVersions.supportsFlywheel(Map.of("minecraft", "1.20.1", "flywheel", "1.0.6")));
        assertTrue(IntegrationVersions.supportsFlywheel(Map.of("minecraft", "1.20.1", "flywheel", "1.0.5")));
    }
    @Test void embeddiumStubIsNotMistakenForASecondOrOlderBackend() {
        assertTrue(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "embeddium", "0.3.31+mc1.20.1", "rubidium", "0.7.1")));
        assertFalse(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "embeddium", "0.4.0", "rubidium", "0.7.1")));
        assertTrue(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "rubidium", "0.7.1")));
    }
    @Test void unknownNativeBufferAndDeferredLambdaVersionsStayDisabled() {
        assertFalse(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "sodium", "0.6.0")));
        assertFalse(IntegrationVersions.supportsFastVertices(Map.of()));
        assertTrue(IntegrationVersions.supportsFastVertices(Map.of("minecraft", "1.20.1", "sodium", "0.5.13+mc1.20.1")));
        assertFalse(IntegrationVersions.supportsMekanismDeferred(Map.of("minecraft", "1.20.1", "mekanism", "10.4.15.75")));
        assertTrue(IntegrationVersions.supportsMekanismDeferred(Map.of("minecraft", "1.20.1", "mekanism", "10.4.16.80")));
        assertTrue(IntegrationVersions.supportsMekanismDeferred(Map.of("minecraft", "1.20.1", "mekanism", "10.4.16")));
    }
    @Test void sameModVersionOnAnotherMinecraftCannotActivateInspectedOneTwentyOneHooks() {
        assertFalse(IntegrationVersions.supportsFlywheel(Map.of("minecraft", "1.21.1", "flywheel", "1.0.5")));
        assertFalse(IntegrationVersions.supportsMekanismDeferred(Map.of("mekanism", "10.4.16")));
        assertFalse(IntegrationVersions.supportsFastVertices(Map.of("sodium", "0.5.13+mc1.20.1")));
    }
    @Test void shaderBufferOverrideRequiresInspectedMinecraftAndShaderVersion() {
        assertFalse(IntegrationVersions.supportsShaderBuffers(Map.of()));
        assertFalse(IntegrationVersions.supportsShaderBuffers(Map.of("minecraft", "1.21.1", "iris", "1.7.6+mc1.20.1")));
        assertFalse(IntegrationVersions.supportsShaderBuffers(Map.of("minecraft", "1.20.1", "iris", "1.8.0")));
        assertTrue(IntegrationVersions.supportsShaderBuffers(Map.of("minecraft", "1.20.1", "iris", "1.7.6+mc1.20.1")));
        assertTrue(IntegrationVersions.supportsShaderBuffers(Map.of("minecraft", "1.20.1", "oculus", "1.8.0")));
    }
}
