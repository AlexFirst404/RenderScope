package dev.renderscope.modern;

import dev.renderscope.client.LoaderInfo;
import dev.renderscope.client.platform.PlatformAccess;
import dev.renderscope.client.platform.PlatformServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModernDisabledHooksTest {
    @TempDir Path config;

    @Test void disabledHooksDoNotAccessGameObjectsOrInitializeTheClient() {
        PlatformServices.install(new PlatformAccess() {
            public Path configDirectory() { return config; }
            public LoaderInfo loader() { return new LoaderInfo("test", "0"); }
            public List<InstalledMod> mods() { return List.of(); }
            public Map<String, String> packageOwners() { return Map.of(); }
        });
        boolean previous = ModernHooks.SETTINGS.profiling;
        ModernHooks.SETTINGS.profiling = false;
        ModernHooks.enterWorld();
        try {
            assertNull(ModernHooks.beginEntity(null, null));
            assertNull(ModernHooks.beginBlockEntity(null, null));
            assertNull(ModernHooks.beginSubmit(null, null));
            assertTrue(ModernHooks.PROFILER.snapshot().instances().isEmpty());
            assertEquals(0, ModernHooks.extractionCalls());
            assertEquals(0, ModernHooks.submissionCalls());
        } finally {
            ModernHooks.leaveWorld();
            ModernHooks.SETTINGS.profiling = previous;
        }
    }
}
