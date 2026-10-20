package dev.renderscope.client.ui;

import dev.renderscope.client.ProfilerSettings;
import dev.renderscope.core.InstanceKey;

/** Main-thread client operations requested by the screen, kept outside profiler state. */
public interface ScreenActions {
    ProfilerSettings settings();

    String modDisplayName(String namespace);

    default boolean hasInstancedRenderer() { return false; }

    /** Persist settings and apply the measurement/window options to the profiler. */
    void saveSettings();

    void toggleHighlight(InstanceKey instance);

    void toggleTrack(InstanceKey instance);

    boolean isHighlighted(InstanceKey instance);

    boolean isTracked(InstanceKey instance);

    /** Zero seconds means a manual capture. */
    void startCapture(int durationSeconds);

    void stopCapture();

    /** Export the latest completed capture, respecting settings.includeWorldPositions. */
    String exportCapture();
}
