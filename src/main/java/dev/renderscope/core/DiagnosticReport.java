package dev.renderscope.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Evidence for a plain-language explanation of measured CPU-side work, not a cause of low FPS.
 * All times are milliseconds. Shares are fractions, not percentages. No value is GPU time.
 */
public record DiagnosticReport(Readiness readiness, int frames, long measuredCalls,
                               double observedSeconds, int minimumFrames, long minimumCalls,
                               double minimumSeconds, double measuredCpuMs, double attributedCpuMs,
                               double sharedCpuMs, double measuredToFrameRatio,
                               double targetFrameBudgetMs, List<ModImpact> rankedMods,
                               Optional<AggregateMetric> worstObject,
                               Optional<InstanceMetric> worstInstance,
                               SpikeEvidence spikes, Set<Reason> reasons) {
    public DiagnosticReport {
        Objects.requireNonNull(readiness, "readiness");
        rankedMods = List.copyOf(rankedMods);
        worstObject = Objects.requireNonNull(worstObject, "worstObject");
        worstInstance = Objects.requireNonNull(worstInstance, "worstInstance");
        Objects.requireNonNull(spikes, "spikes");
        reasons = Set.copyOf(reasons);
    }

    public boolean ready() { return readiness == Readiness.READY; }
    public Optional<ModImpact> topMod() { return rankedMods.stream().findFirst(); }
    public boolean coverageLimited() { return reasons.contains(Reason.LIMITED_SCOPE); }
    public boolean parallelSources() { return reasons.contains(Reason.PARALLEL_VISUAL_SOURCES); }
    public boolean measurementsDropped() { return reasons.contains(Reason.DROPPED_MEASUREMENTS); }

    public enum Readiness { DISABLED, NO_SAMPLES, COLLECTING, READY }

    /** UI adapters localize these codes; none asserts that removing a mod would improve FPS. */
    public enum Reason {
        LIMITED_SCOPE,
        CPU_NOT_GPU,
        NO_CAUSAL_FPS_PREDICTION,
        PROFILING_DISABLED,
        NO_OBJECT_SAMPLES,
        INSUFFICIENT_DATA,
        LOW_MEASURED_SLICE,
        PARALLEL_VISUAL_SOURCES,
        INTERVAL_SUM_EXCEEDS_FRAME,
        DROPPED_MEASUREMENTS,
        SHARED_WORK_SEPARATE,
        REGISTRY_RENDERER_OWNER_DIFFERS
    }

    /**
     * Share of the attributable mod CPU intervals only, excluding shared phases.
     * targetBudgetRatio compares summed task intervals to a chosen frame budget. It can exceed
     * one and is never a predicted frame-time reduction, GPU share or obtainable FPS gain.
     */
    public record ModImpact(AggregateMetric mod, double measuredCpuShare, double targetBudgetRatio) {
        public ModImpact { Objects.requireNonNull(mod, "mod"); }
    }

    public enum SpikeRelevance { NO_RETAINED_SPIKES, NO_LEADER, LEADER_LISTED, LEADER_NOT_LISTED }

    /**
     * Only retained spike frames inside the rolling window. The timeline keeps up to ten samples
     * per second, so these counts are not the number or frequency of every actual spike.
     * topModMs contains only five owners: LEADER_NOT_LISTED does not establish absence or innocence.
     * A listed owner coincides with a spike; this is not causal attribution of the whole frame.
     */
    public record SpikeEvidence(int retainedFrames, int leaderListedFrames, double largestFrameMs,
                                double leaderPeakMs, Optional<FramePoint> worstFrame,
                                SpikeRelevance relevance) {
        public SpikeEvidence {
            worstFrame = Objects.requireNonNull(worstFrame, "worstFrame");
            Objects.requireNonNull(relevance, "relevance");
        }
    }
}
