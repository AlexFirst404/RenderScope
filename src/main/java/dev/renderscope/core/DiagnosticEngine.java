package dev.renderscope.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static dev.renderscope.core.DiagnosticReport.*;

/** Stateless interpretation of an immutable snapshot. No game objects or previous worlds are retained. */
public final class DiagnosticEngine {
    public static final int MINIMUM_FRAMES = 20;
    public static final long MINIMUM_CALLS = 20;
    public static final double MINIMUM_SECONDS = 1;
    private static final double SMALL_MEASURED_SLICE = 0.15;
    private static final Comparator<AggregateMetric> MOST_WORK = Comparator
        .comparingDouble((AggregateMetric metric) -> positive(metric.stats().averageMs())).reversed()
        .thenComparing(AggregateMetric::id)
        .thenComparing(metric -> String.valueOf(metric.kind()));

    private DiagnosticEngine() { }

    /** Uses 60 FPS only as a reference workload budget; no FPS improvement is inferred. */
    public static DiagnosticReport analyze(ProfilerSnapshot snapshot) { return analyze(snapshot, 60); }

    public static DiagnosticReport analyze(ProfilerSnapshot snapshot, double targetFps) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Double.isFinite(targetFps) || targetFps <= 0) {
            throw new IllegalArgumentException("targetFps must be finite and positive");
        }
        double budgetMs = 1000 / targetFps;
        if (!Double.isFinite(budgetMs) || budgetMs <= 0) {
            throw new IllegalArgumentException("targetFps must produce a finite positive frame budget");
        }
        Performance performance = snapshot.performance();
        int frames = Math.max(0, performance.frames());
        double frameMs = positive(performance.averageFrameMs());
        // Actual accumulated frame durations, not the configured maximum rolling-window length.
        double observedSeconds = positive(frameMs * frames / 1000);
        // Rolling expiry and frame-boundary gaps keep a one-second window slightly below 1.0s.
        double minimumSeconds = Double.isFinite(snapshot.windowSeconds()) && snapshot.windowSeconds() > 0
            ? Math.min(MINIMUM_SECONDS, snapshot.windowSeconds() * 0.8) : MINIMUM_SECONDS;
        double measuredMs = positive(performance.measuredRenderMs());
        double sharedMs = 0;
        for (PhaseMetric phase : snapshot.phases()) sharedMs = sum(sharedMs, positive(phase.stats().averageMs()));

        List<AggregateMetric> candidates = snapshot.mods().stream()
            .filter(metric -> metric.stats().calls() > 0 && positive(metric.stats().averageMs()) > 0)
            .sorted(MOST_WORK).toList();
        double attributedMs = 0;
        long calls = 0;
        for (AggregateMetric mod : candidates) {
            attributedMs = sum(attributedMs, positive(mod.stats().averageMs()));
            calls = saturatedAdd(calls, mod.stats().calls());
        }
        List<ModImpact> ranked = new ArrayList<>(candidates.size());
        for (AggregateMetric mod : candidates) {
            double ms = positive(mod.stats().averageMs());
            ranked.add(new ModImpact(mod, ratio(ms, attributedMs), ratio(ms, budgetMs)));
        }

        EnumSet<Reason> reasons = EnumSet.of(Reason.LIMITED_SCOPE, Reason.CPU_NOT_GPU,
            Reason.NO_CAUSAL_FPS_PREDICTION);
        boolean noSamples = frames == 0 || ranked.isEmpty();
        boolean insufficient = frames < MINIMUM_FRAMES || calls < MINIMUM_CALLS
            || observedSeconds < minimumSeconds;
        Readiness readiness = !snapshot.enabled() ? Readiness.DISABLED
            : noSamples ? Readiness.NO_SAMPLES : insufficient ? Readiness.COLLECTING : Readiness.READY;
        if (!snapshot.enabled()) reasons.add(Reason.PROFILING_DISABLED);
        if (noSamples) reasons.add(Reason.NO_OBJECT_SAMPLES);
        else if (insufficient) reasons.add(Reason.INSUFFICIENT_DATA);
        if (snapshot.droppedMeasurements() > 0) reasons.add(Reason.DROPPED_MEASUREMENTS);
        if (sharedMs > 0) reasons.add(Reason.SHARED_WORK_SEPARATE);
        if (hasParallelSources(candidates)) reasons.add(Reason.PARALLEL_VISUAL_SOURCES);
        double allMeasuredMs = sum(measuredMs, sharedMs);
        if (frameMs > 0 && allMeasuredMs > frameMs + 0.000001) reasons.add(Reason.INTERVAL_SUM_EXCEEDS_FRAME);
        // This is only a small observed slice, never a percentage of all rendering or GPU coverage.
        if (frames > 0 && frameMs > 0 && allMeasuredMs / frameMs < SMALL_MEASURED_SLICE) {
            reasons.add(Reason.LOW_MEASURED_SLICE);
        }

        Optional<AggregateMetric> object = Optional.empty();
        Optional<InstanceMetric> instance = Optional.empty();
        String leaderId = ranked.isEmpty() ? null : ranked.get(0).mod().id();
        if (leaderId != null) {
            object = snapshot.objects().stream()
                .filter(metric -> leaderId.equals(metric.registryOwner()) && metric.stats().calls() > 0
                    && positive(metric.stats().averageMs()) > 0)
                .min(MOST_WORK);
            if (object.isPresent()) {
                AggregateMetric selected = object.get();
                instance = snapshot.instances().stream()
                    .filter(metric -> leaderId.equals(metric.context().registryOwner())
                        && selected.id().equals(metric.context().registryId())
                        && selected.kind() == metric.context().key().kind()
                        && metric.stats().calls() > 0 && positive(metric.stats().averageMs()) > 0)
                    .min(Comparator.comparingDouble((InstanceMetric metric) -> positive(metric.stats().averageMs()))
                        .reversed().thenComparing(metric -> metric.context().key().identity()));
            }
            if (snapshot.renderers().stream().anyMatch(metric -> leaderId.equals(metric.registryOwner())
                && !Objects.equals(metric.registryOwner(), metric.rendererOwner())
                && metric.stats().calls() > 0)) reasons.add(Reason.REGISTRY_RENDERER_OWNER_DIFFERS);
        }
        return new DiagnosticReport(readiness, frames, calls, observedSeconds, MINIMUM_FRAMES, MINIMUM_CALLS,
            minimumSeconds, measuredMs, attributedMs, sharedMs, ratio(measuredMs, frameMs), budgetMs,
            ranked, object, instance, spikeEvidence(snapshot, frames, leaderId), reasons);
    }

    private static boolean hasParallelSources(List<AggregateMetric> mods) {
        for (AggregateMetric mod : mods) {
            for (MeasurementSource source : List.of(MeasurementSource.FLYWHEEL_VISUAL_FRAME,
                MeasurementSource.FLYWHEEL_VISUAL_TICK, MeasurementSource.FLYWHEEL_VISUAL_LIGHT)) {
                SourceMetric metric = mod.stats().sources().get(source);
                if (metric != null && metric.calls() > 0) return true;
            }
        }
        return false;
    }

    private static SpikeEvidence spikeEvidence(ProfilerSnapshot snapshot, int frames, String leaderId) {
        long latest = snapshot.timeline().stream().mapToLong(FramePoint::timestampNanos).max().orElse(0);
        double seconds = Double.isFinite(snapshot.windowSeconds()) && snapshot.windowSeconds() > 0
            ? Math.min(30, snapshot.windowSeconds()) : 5;
        long span = (long) (seconds * 1_000_000_000L);
        long cutoff = latest < Long.MIN_VALUE + span ? Long.MIN_VALUE : latest - span;
        int retained = 0, listed = 0;
        double leaderPeak = 0;
        FramePoint worst = null;
        if (frames > 0) for (FramePoint point : snapshot.timeline()) {
            if (!point.spike() || point.timestampNanos() < cutoff || positive(point.frameMs()) == 0) continue;
            retained++;
            double ownerMs = leaderId == null ? 0 : positive(point.topModMs().getOrDefault(leaderId, 0.0));
            if (ownerMs > 0) { listed++; leaderPeak = Math.max(leaderPeak, ownerMs); }
            if (worst == null || point.frameMs() > worst.frameMs()
                || point.frameMs() == worst.frameMs() && point.timestampNanos() > worst.timestampNanos()) worst = point;
        }
        SpikeRelevance relevance = retained == 0 ? SpikeRelevance.NO_RETAINED_SPIKES
            : leaderId == null ? SpikeRelevance.NO_LEADER
            : listed > 0 ? SpikeRelevance.LEADER_LISTED : SpikeRelevance.LEADER_NOT_LISTED;
        return new SpikeEvidence(retained, listed, worst == null ? 0 : worst.frameMs(), leaderPeak,
            Optional.ofNullable(worst), relevance);
    }

    private static double positive(double value) { return Double.isFinite(value) && value > 0 ? value : 0; }
    private static double sum(double left, double right) { return left > Double.MAX_VALUE - right ? Double.MAX_VALUE : left + right; }
    private static double ratio(double value, double divisor) {
        if (divisor <= 0 || value <= 0) return 0;
        double result = value / divisor;
        return Double.isInfinite(result) ? Double.MAX_VALUE : positive(result);
    }
    private static long saturatedAdd(long left, long right) { return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right; }
}
