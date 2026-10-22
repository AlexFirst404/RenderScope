package dev.renderscope.core;

/** The actual CPU work observed; none of these values describes GPU execution time. */
public enum MeasurementSource {
    RENDERER,
    RENDER_EXTRACTION,
    RENDER_SUBMISSION,
    FLYWHEEL_VISUAL_CREATE,
    FLYWHEEL_VISUAL_FRAME,
    FLYWHEEL_VISUAL_TICK,
    FLYWHEEL_VISUAL_UPDATE,
    FLYWHEEL_VISUAL_LIGHT,
    FLYWHEEL_BATCH_SUBMIT;

    public boolean visualWork() {
        return this == FLYWHEEL_VISUAL_CREATE || this == FLYWHEEL_VISUAL_FRAME || this == FLYWHEEL_VISUAL_TICK
                || this == FLYWHEEL_VISUAL_UPDATE || this == FLYWHEEL_VISUAL_LIGHT;
    }
}
