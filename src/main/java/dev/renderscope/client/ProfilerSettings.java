package dev.renderscope.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** User preferences, accessed on the Minecraft client thread. Never contains world data. */
public final class ProfilerSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope/Settings");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum MeasurementMode { TIMING_ONLY, FULL }

    public boolean profiling = true;
    public MeasurementMode mode = MeasurementMode.TIMING_ONLY;
    public boolean cpuTiming = false;
    public int sampleWindowSeconds = 5;
    public boolean inspect = false;
    public boolean heatmap = false;
    public boolean heatmapEntities = true;
    public boolean heatmapBlockEntities = true;
    public int maximumDistance = 64;
    public int maxHighlightedObjects = 100;
    public double minimumCostMs = 0.05;
    public boolean showLabels = true;
    public boolean throughWalls = false;
    public int highlightTimeoutSeconds = 15;
    public boolean includeWorldPositions = false;
    public int captureDurationSeconds = 10;

    private transient Path configFile;

    public static ProfilerSettings load(Path configFile) {
        ProfilerSettings settings = new ProfilerSettings();
        if (Files.isRegularFile(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                ProfilerSettings parsed = GSON.fromJson(reader, ProfilerSettings.class);
                if (parsed != null) settings = parsed;
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Could not read RenderScope preferences; using defaults", exception);
            }
        }
        settings.configFile = configFile.toAbsolutePath().normalize();
        settings.sanitize();
        return settings;
    }

    public void sanitize() {
        if (mode == null) mode = MeasurementMode.TIMING_ONLY;
        sampleWindowSeconds = Math.max(1, Math.min(30, sampleWindowSeconds));
        maximumDistance = Math.max(8, Math.min(256, maximumDistance));
        maxHighlightedObjects = Math.max(1, Math.min(500, maxHighlightedObjects));
        minimumCostMs = Double.isFinite(minimumCostMs) ? Math.max(0, Math.min(1000, minimumCostMs)) : 0.05;
        highlightTimeoutSeconds = Math.max(0, Math.min(300, highlightTimeoutSeconds));
        if (captureDurationSeconds != 0 && captureDurationSeconds != 5
                && captureDurationSeconds != 10 && captureDurationSeconds != 30) {
            captureDurationSeconds = 10;
        }
    }

    /** Save atomically, so an interrupted client does not leave a truncated JSON file. */
    public boolean save() {
        sanitize();
        if (configFile == null) return false;
        Path temporaryFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temporaryFile, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not save RenderScope preferences", exception);
            return false;
        }
    }
}
