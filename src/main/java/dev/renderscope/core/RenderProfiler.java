package dev.renderscope.core;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Loader-independent renderer profiler. All retained data consists of values: game objects,
 * renderers, worlds and vertex consumers are never retained here. Calls may come from multiple
 * threads; late completions from a closed frame or an old world session are discarded.
 */
public final class RenderProfiler {
    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final long SNAPSHOT_INTERVAL = 250_000_000L;
    private static final long TIMELINE_INTERVAL = 100_000_000L;
    private static final long TIMELINE_WINDOW = 30 * NS_PER_SECOND;
    private static final int MAX_FRAME_INSTANCES = 4096;
    private static final int MAX_ROLLING_FRAMES = 8192;
    private static final int MAX_ROLLING_SAMPLES = 262144;
    private static final int MAX_CAPTURE_KEYS = 4096;
    private static final int MAX_RENDER_TYPES = 64;
    private final LongSupplier nanoClock;
    private final LongSupplier cpuClock;
    private final LongSupplier epochClock;
    private final Deque<Frame> rolling = new ArrayDeque<>();
    private final Deque<FramePoint> timeline = new ArrayDeque<>();
    private Aggregation rollingAggregate = new Aggregation(true);
    private int sampleWindowSeconds = 5;
    private boolean enabled = true;
    private boolean cpuTimingEnabled;
    private long generation;
    private long nextFrame;
    private long droppedMeasurements;
    private int retainedSamples;
    private double frameBaselineNs;
    private Frame current;
    private ProfilerSnapshot cached;
    private long cachedAt;
    private Capture capture;
    private CaptureReport lastCapture;

    public RenderProfiler() {
        this(System::nanoTime, defaultCpuClock(), System::currentTimeMillis);
    }

    RenderProfiler(LongSupplier nanoClock, LongSupplier cpuClock, LongSupplier epochClock) {
        this.nanoClock = nanoClock;
        this.cpuClock = cpuClock;
        this.epochClock = epochClock;
    }

    private static LongSupplier defaultCpuClock() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return () -> {
            try {
                return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                    ? bean.getCurrentThreadCpuTime() : -1L;
            } catch (UnsupportedOperationException | SecurityException ignored) {
                return -1L;
            }
        };
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        cached = null;
    }

    public synchronized void setCpuTimingEnabled(boolean enabled) {
        cpuTimingEnabled = enabled;
    }

    public synchronized void setSampleWindowSeconds(int seconds) {
        if (seconds < 1 || seconds > 30) {
            throw new IllegalArgumentException("Sample window must be between 1 and 30 seconds");
        }
        sampleWindowSeconds = seconds;
        prune(nanoClock.getAsLong());
        cached = null;
    }

    /** Start a full frame. A previously unclosed frame is abandoned, never combined. */
    public synchronized void beginFrame() {
        if (current != null) droppedMeasurements += current.calls;
        current = new Frame(++nextFrame, nanoClock.getAsLong());
    }

    /** Returns null when profiling is disabled or no frame is active. */
    public synchronized CallToken beginCall(SampleContext context) {
        if (!enabled || current == null) return null;
        return new CallToken(this, generation, current.id, context, nanoClock.getAsLong(),
            cpuTimingEnabled ? cpuClock.getAsLong() : -1, Thread.currentThread().getId());
    }

    /** An adapter intercepted real work but could not retain an unambiguous bounded association. */
    public synchronized void recordDroppedMeasurement() {
        if (enabled && current != null) droppedMeasurements = saturatedAdd(droppedMeasurements, 1);
    }

    /** Finish in a finally block. A failed renderer is still measured. */
    public synchronized void endCall(CallToken token, long vertices, long estimatedBytes,
                                     Set<String> renderTypes, boolean failure) {
        if (token == null || token.owner != this || token.finished) return;
        token.finished = true;
        if (token.generation != generation || current == null || token.frame != current.id) return;
        token.rawWall = Math.max(0, nanoClock.getAsLong() - token.started);
        token.rawCpu = 0;
        if (token.cpuStarted >= 0 && token.threadId == Thread.currentThread().getId()) {
            long end = cpuClock.getAsLong();
            if (end >= token.cpuStarted) token.rawCpu = end - token.cpuStarted;
        }
        token.validMeasurement = true;
        long elapsed = Math.max(0, token.rawWall - token.excludedWall);
        long cpu = Math.max(0, token.rawCpu - token.excludedCpu);
        current.calls++;
        current.wall += elapsed;
        current.cpu += cpu;
        current.vertices += Math.max(0, vertices);
        current.bytes += Math.max(0, estimatedBytes);
        if (token.context.key().kind() == ObjectKind.ENTITY) current.entityWall += elapsed;
        else current.blockWall += elapsed;
        CallSample sample = current.instances.get(token.context.key());
        if (sample == null) {
            if (current.instances.size() >= MAX_FRAME_INSTANCES) {
                droppedMeasurements++;
                return;
            }
            sample = new CallSample(token.context);
            current.instances.put(token.context.key(), sample);
        }
        sample.context = token.context;
        sample.wall += elapsed;
        sample.cpu += cpu;
        sample.calls++;
        if (failure) sample.failures++;
        sample.vertices += Math.max(0, vertices);
        sample.bytes += Math.max(0, estimatedBytes);
        sample.lastSeen = nanoClock.getAsLong();
        sample.addTypes(renderTypes);
    }

    /**
     * Exclude a completed direct child from its still-active parent. The child's raw inclusive
     * duration is subtracted: any grandchildren were already removed from the child's own
     * recorded measurement, but still belong inside the interval excluded from the parent.
     * Call after endCall(child) and before endCall(parent), on the original rendering thread.
     * Stale, cross-thread, unrecorded and repeated exclusions are ignored.
     */
    public synchronized void excludeNestedCall(CallToken parent, CallToken completedChild) {
        if (parent == null || completedChild == null || parent == completedChild
            || parent.owner != this || completedChild.owner != this || parent.finished
            || !completedChild.finished || !completedChild.validMeasurement || completedChild.excludedFromParent
            || current == null || parent.generation != generation || completedChild.generation != generation
            || parent.frame != current.id || completedChild.frame != parent.frame
            || parent.threadId != completedChild.threadId || parent.threadId != Thread.currentThread().getId()
            || completedChild.started < parent.started) return;
        parent.excludedWall = saturatedAdd(parent.excludedWall, completedChild.rawWall);
        parent.excludedCpu = saturatedAdd(parent.excludedCpu, completedChild.rawCpu);
        completedChild.excludedFromParent = true;
    }

    public synchronized void endFrame() {
        if (current != null) completeFrame(Math.max(1, nanoClock.getAsLong() - current.started));
    }

    /** Supply frame duration measured at the outer game-loop boundary, including frame pacing. */
    public synchronized void endFrame(long frameDurationNanos) {
        if (current != null) completeFrame(Math.max(1, frameDurationNanos));
    }

    private void completeFrame(long duration) {
        long now = nanoClock.getAsLong();
        Frame frame = current;
        current = null;
        frame.ended = now;
        frame.duration = duration;
        frame.prepareGroups();
        frame.spike = frameBaselineNs > 0 && duration > Math.max(33_333_333, frameBaselineNs * 1.75);
        frameBaselineNs = frameBaselineNs == 0 ? duration : frameBaselineNs * 0.95 + duration * 0.05;
        rolling.addLast(frame);
        rollingAggregate.addFrame(frame);
        retainedSamples += frame.retainedSize();
        prune(now);
        appendTimeline(frame);
        if (capture != null) {
            if (frame.started >= capture.started) capture.aggregate.addFrame(frame);
            if (capture.durationSeconds > 0 && now - capture.started >= capture.durationSeconds * NS_PER_SECOND) {
                finishCapture(now);
            }
        }
    }

    private void prune(long now) {
        long cutoff = now - sampleWindowSeconds * NS_PER_SECOND;
        while (!rolling.isEmpty() && (rolling.peekFirst().ended < cutoff
            || rolling.size() > MAX_ROLLING_FRAMES || retainedSamples > MAX_ROLLING_SAMPLES)) {
            Frame removed = rolling.removeFirst();
            rollingAggregate.removeFrame(removed);
            retainedSamples -= removed.retainedSize();
            if (removed.ended >= cutoff) droppedMeasurements += Math.max(1, removed.instances.size());
        }
        while (!timeline.isEmpty() && (timeline.peekFirst().timestampNanos() < now - TIMELINE_WINDOW
            || timeline.size() > 301)) timeline.removeFirst();
    }

    private void appendTimeline(Frame frame) {
        FramePoint point = null;
        if (capture != null && frame.started >= capture.started && capture.acceptsTimeline(frame)) {
            point = framePoint(frame);
            capture.appendTimeline(point);
        }
        FramePoint previous = timeline.peekLast();
        long bucket = Math.floorDiv(frame.ended, TIMELINE_INTERVAL);
        if (previous != null && Math.floorDiv(previous.timestampNanos(), TIMELINE_INTERVAL) == bucket) {
            // A spike always survives its bucket; otherwise preserve the slowest actual frame.
            if (previous.spike() && !frame.spike
                || previous.spike() == frame.spike && previous.frameMs() >= ms(frame.duration)) return;
            timeline.removeLast();
        }
        if (point == null) point = framePoint(frame);
        timeline.addLast(point);
    }

    private FramePoint framePoint(Frame frame) {
        Map<String, Long> modTimes = new HashMap<>();
        CallSample worst = null;
        for (CallSample sample : frame.instances.values()) {
            modTimes.merge(sample.context.registryOwner(), sample.wall, Long::sum);
            if (worst == null || sample.wall > worst.wall) worst = sample;
        }
        Map<String, Double> top = new LinkedHashMap<>();
        modTimes.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5).forEach(e -> top.put(e.getKey(), ms(e.getValue())));
        InstanceMetric instance = worst == null ? null : new InstanceMetric(worst.context,
            singleFrameStats(worst), worst.lastSeen);
        return new FramePoint(frame.ended, ms(frame.duration), ms(frame.wall), ms(frame.entityWall),
            ms(frame.blockWall), frame.spike, top, instance);
    }

    public synchronized ProfilerSnapshot snapshot() {
        long now = nanoClock.getAsLong();
        if (cached != null && now - cachedAt < SNAPSHOT_INTERVAL) return cached;
        prune(now);
        if (capture != null && capture.durationSeconds > 0
            && now - capture.started >= capture.durationSeconds * NS_PER_SECOND) finishCapture(now);
        Aggregation aggregate = rollingAggregate;
        CaptureStatus status = capture == null ? new CaptureStatus(false, 0, 0)
            : new CaptureStatus(true, seconds(now - capture.started), capture.durationSeconds);
        cached = new ProfilerSnapshot(enabled, sampleWindowSeconds, aggregate.performance(),
            aggregate.mods(), aggregate.renderers(), aggregate.objects(), aggregate.instances(),
            new ArrayList<>(timeline), status, droppedMeasurements + aggregate.dropped);
        cachedAt = now;
        return cached;
    }

    /** 0 means manual. Fixed captures accept the durations offered by the UI. */
    public synchronized void startCapture(int seconds) {
        if (seconds != 0 && seconds != 5 && seconds != 10 && seconds != 30) {
            throw new IllegalArgumentException("Capture duration must be 0, 5, 10 or 30 seconds");
        }
        capture = new Capture(nanoClock.getAsLong(), epochClock.getAsLong(), seconds, droppedMeasurements);
        lastCapture = null;
        cached = null;
    }

    public synchronized CaptureReport stopCapture() {
        if (capture != null) finishCapture(nanoClock.getAsLong());
        return lastCapture;
    }

    public synchronized CaptureReport lastCapture() { return lastCapture; }

    private void finishCapture(long now) {
        Capture completed = capture;
        capture = null;
        Aggregation a = completed.aggregate;
        lastCapture = new CaptureReport(completed.epochMillis, seconds(now - completed.started),
            a.frames, a.performance(), a.mods(), a.renderers(), a.objects(), a.instances(),
            completed.timeline, droppedMeasurements - completed.initialDropped + a.dropped);
        cached = null;
    }

    /** Called on disconnect and world changes. Also invalidates outstanding call tokens. */
    public synchronized void clear() {
        generation++;
        current = null;
        rolling.clear();
        rollingAggregate = new Aggregation(true);
        timeline.clear();
        retainedSamples = 0;
        frameBaselineNs = 0;
        droppedMeasurements = 0;
        capture = null;
        lastCapture = null;
        cached = null;
    }

    public static final class CallToken {
        private final RenderProfiler owner;
        private final long generation;
        private final long frame;
        private final SampleContext context;
        private final long started;
        private final long cpuStarted;
        private final long threadId;
        private boolean finished;
        private boolean validMeasurement;
        private boolean excludedFromParent;
        private long rawWall;
        private long rawCpu;
        private long excludedWall;
        private long excludedCpu;

        private CallToken(RenderProfiler owner, long generation, long frame, SampleContext context,
                          long started, long cpuStarted, long threadId) {
            this.owner = owner;
            this.generation = generation;
            this.frame = frame;
            this.context = java.util.Objects.requireNonNull(context, "context");
            this.started = started;
            this.cpuStarted = cpuStarted;
            this.threadId = threadId;
        }
    }

    private static final class Frame {
        final long id;
        final long started;
        final Map<InstanceKey, CallSample> instances = new HashMap<>();
        final Map<String, CallSample> mods = new HashMap<>();
        final Map<String, CallSample> renderers = new HashMap<>();
        final Map<String, CallSample> objects = new HashMap<>();
        long ended;
        long duration;
        long wall;
        long cpu;
        long entityWall;
        long blockWall;
        long calls;
        long vertices;
        long bytes;
        boolean spike;

        Frame(long id, long started) { this.id = id; this.started = started; }

        void prepareGroups() {
            for (CallSample sample : instances.values()) {
                merge(mods, sample.context.registryOwner(), sample);
                merge(renderers, sample.context.rendererClass(), sample);
                merge(objects, sample.objectKey, sample);
            }
        }

        int retainedSize() { return instances.size() + mods.size() + renderers.size() + objects.size(); }

        private static void merge(Map<String, CallSample> map, String id, CallSample sample) {
            map.computeIfAbsent(id, ignored -> new CallSample(sample.context)).merge(sample);
        }
    }

    private static final class CallSample {
        SampleContext context;
        long wall;
        long cpu;
        long calls;
        long failures;
        long vertices;
        long bytes;
        long lastSeen;
        final String objectKey;
        Set<String> renderTypes;

        CallSample(SampleContext context) {
            this.context = context;
            objectKey = context.key().kind().name() + ":" + context.registryId();
        }

        void merge(CallSample other) {
            wall += other.wall;
            cpu += other.cpu;
            calls += other.calls;
            failures += other.failures;
            vertices += other.vertices;
            bytes += other.bytes;
            if (other.lastSeen >= lastSeen) { context = other.context; lastSeen = other.lastSeen; }
            addTypes(other.renderTypes);
        }

        void addTypes(Set<String> types) {
            if (types == null || types.isEmpty()) return;
            if (renderTypes == null) renderTypes = new HashSet<>();
            RenderProfiler.addTypes(renderTypes, types);
        }
    }

    /** Missing per-frame observations are represented by zeros when statistics are read. */
    private static final class MetricAccumulator {
        final String id;
        final ObjectKind kind;
        SampleContext context;
        final Distribution distribution;
        final Map<InstanceKey, Integer> instances = new HashMap<>();
        final Set<String> types = new HashSet<>();
        final Map<String, Integer> typeCounts;
        long wall;
        long cpu;
        long calls;
        long failures;
        long vertices;
        long bytes;
        long peak;
        long lastSeen;

        MetricAccumulator(String id, ObjectKind kind, SampleContext context, boolean exact) {
            this.id = id; this.kind = kind; this.context = context;
            distribution = new Distribution(exact);
            typeCounts = exact ? new HashMap<>() : null;
        }

        void observe(CallSample sample) {
            distribution.add(sample.wall);
            wall += sample.wall;
            cpu += sample.cpu;
            calls += sample.calls;
            failures += sample.failures;
            vertices += sample.vertices;
            bytes += sample.bytes;
            peak = Math.max(peak, sample.wall);
            if (sample.lastSeen >= lastSeen) { context = sample.context; lastSeen = sample.lastSeen; }
            if (typeCounts == null) addTypes(types, sample.renderTypes);
            else if (sample.renderTypes != null) for (String type : sample.renderTypes) {
                typeCounts.merge(type, 1, Integer::sum);
            }
        }

        void remove(CallSample sample) {
            distribution.remove(sample.wall);
            wall -= sample.wall;
            cpu -= sample.cpu;
            calls -= sample.calls;
            failures -= sample.failures;
            vertices -= sample.vertices;
            bytes -= sample.bytes;
            if (sample.renderTypes != null) for (String type : sample.renderTypes) {
                Integer count = typeCounts.get(type);
                if (count == null) continue;
                if (count == 1) typeCounts.remove(type);
                else typeCounts.put(type, count - 1);
            }
        }

        Statistics statistics(int frames) {
            double divisor = Math.max(1, frames);
            Set<String> visibleTypes = types;
            if (typeCounts != null) {
                visibleTypes = new HashSet<>();
                for (String type : typeCounts.keySet()) {
                    if (visibleTypes.size() >= MAX_RENDER_TYPES) break;
                    visibleTypes.add(type);
                }
            }
            return new Statistics(ms(wall / divisor), ms(distribution.percentile(0.95, frames)),
                ms(distribution.isExact() ? distribution.maximum() : peak), ms(cpu / divisor), calls / divisor, calls, failures, vertices / divisor,
                bytes / divisor, instances.size(), visibleTypes);
        }
    }

    private static final class Aggregation {
        final boolean exact;
        final Map<String, MetricAccumulator> mods = new HashMap<>();
        final Map<String, MetricAccumulator> renderers = new HashMap<>();
        final Map<String, MetricAccumulator> objects = new HashMap<>();
        final Map<InstanceKey, MetricAccumulator> instances = new HashMap<>();
        final Distribution frameTimes;
        int frames;
        long wall;
        long entityWall;
        long blockWall;
        long frameTotal;
        long frameWorst;
        long currentFrame;
        long calls;
        long vertices;
        long bytes;
        long entityCount;
        long blockCount;
        long dropped;

        Aggregation(boolean exact) { this.exact = exact; frameTimes = new Distribution(exact); }

        void addFrame(Frame frame) {
            frames++;
            frameTotal += frame.duration;
            currentFrame = frame.duration;
            frameWorst = Math.max(frameWorst, frame.duration);
            frameTimes.add(frame.duration);
            wall += frame.wall;
            entityWall += frame.entityWall;
            blockWall += frame.blockWall;
            calls += frame.calls;
            vertices += frame.vertices;
            bytes += frame.bytes;
            observe(mods, frame.mods, false);
            observe(renderers, frame.renderers, false);
            observe(objects, frame.objects, true);
            for (Map.Entry<InstanceKey, CallSample> entry : frame.instances.entrySet()) {
                InstanceKey key = entry.getKey();
                CallSample sample = entry.getValue();
                if (key.kind() == ObjectKind.ENTITY) entityCount++; else blockCount++;
                MetricAccumulator instance = instances.get(key);
                if (instance == null && (exact || instances.size() < MAX_CAPTURE_KEYS)) {
                    instance = new MetricAccumulator(sample.context.registryId(), key.kind(), sample.context, exact);
                    instances.put(key, instance);
                }
                if (instance != null) { instance.observe(sample); trackInstance(instance, key); }
                else dropped++;
                trackInstance(mods.get(sample.context.registryOwner()), key);
                trackInstance(renderers.get(sample.context.rendererClass()), key);
                trackInstance(objects.get(sample.objectKey), key);
            }
        }

        private static void trackInstance(MetricAccumulator metric, InstanceKey key) {
            if (metric == null) return;
            if (metric.distribution.isExact() || metric.instances.containsKey(key) || metric.instances.size() < MAX_CAPTURE_KEYS) {
                if (metric.distribution.isExact()) metric.instances.merge(key, 1, Integer::sum);
                else metric.instances.putIfAbsent(key, 1);
            }
        }

        void removeFrame(Frame frame) {
            if (!exact) throw new IllegalStateException("Only rolling aggregates may expire frames");
            frames--;
            frameTotal -= frame.duration;
            frameTimes.remove(frame.duration);
            wall -= frame.wall;
            entityWall -= frame.entityWall;
            blockWall -= frame.blockWall;
            calls -= frame.calls;
            vertices -= frame.vertices;
            bytes -= frame.bytes;
            for (Map.Entry<InstanceKey, CallSample> entry : frame.instances.entrySet()) {
                InstanceKey key = entry.getKey();
                CallSample sample = entry.getValue();
                if (key.kind() == ObjectKind.ENTITY) entityCount--; else blockCount--;
                untrackInstance(mods.get(sample.context.registryOwner()), key);
                untrackInstance(renderers.get(sample.context.rendererClass()), key);
                untrackInstance(objects.get(sample.objectKey), key);
                removeMetric(instances, key, sample);
            }
            for (Map.Entry<String, CallSample> entry : frame.mods.entrySet()) removeMetric(mods, entry.getKey(), entry.getValue());
            for (Map.Entry<String, CallSample> entry : frame.renderers.entrySet()) removeMetric(renderers, entry.getKey(), entry.getValue());
            for (Map.Entry<String, CallSample> entry : frame.objects.entrySet()) removeMetric(objects, entry.getKey(), entry.getValue());
            if (frames == 0) currentFrame = 0;
        }

        private static <K> void removeMetric(Map<K, MetricAccumulator> map, K key, CallSample sample) {
            MetricAccumulator metric = map.get(key);
            if (metric == null) return;
            metric.remove(sample);
            if (metric.distribution.count == 0) map.remove(key);
        }

        private static void untrackInstance(MetricAccumulator metric, InstanceKey key) {
            if (metric == null) return;
            Integer count = metric.instances.get(key);
            if (count == null) return;
            if (count == 1) metric.instances.remove(key);
            else metric.instances.put(key, count - 1);
        }

        private void observe(Map<String, MetricAccumulator> destination, Map<String, CallSample> source,
                             boolean object) {
            for (Map.Entry<String, CallSample> entry : source.entrySet()) {
                CallSample sample = entry.getValue();
                MetricAccumulator metric = destination.get(entry.getKey());
                if (metric == null) {
                    if (!exact && destination.size() >= MAX_CAPTURE_KEYS) { dropped++; continue; }
                    String id = object ? sample.context.registryId() : entry.getKey();
                    metric = new MetricAccumulator(id, object ? sample.context.key().kind() : null,
                        sample.context, exact);
                    destination.put(entry.getKey(), metric);
                }
                metric.observe(sample);
            }
        }

        Performance performance() {
            double divisor = Math.max(1, frames);
            double mean = frameTotal / divisor;
            double slowMean = frameTimes.meanSlowestFraction(0.01, frames);
            return new Performance(frames, mean > 0 ? NS_PER_SECOND / mean : 0, ms(currentFrame),
                ms(mean), slowMean > 0 ? NS_PER_SECOND / slowMean : 0,
                ms(frameTimes.percentile(0.50, frames)), ms(frameTimes.percentile(0.95, frames)),
                ms(frameTimes.percentile(0.99, frames)), ms(exact ? frameTimes.maximum() : frameWorst), ms(wall / divisor),
                ms(entityWall / divisor), ms(blockWall / divisor), calls / divisor,
                entityCount / divisor, blockCount / divisor, instances.size(), vertices / divisor,
                bytes / divisor);
        }

        List<AggregateMetric> mods() { return aggregates(mods); }
        List<AggregateMetric> renderers() { return aggregates(renderers); }
        List<AggregateMetric> objects() { return aggregates(objects); }

        private List<AggregateMetric> aggregates(Map<String, MetricAccumulator> map) {
            List<AggregateMetric> result = new ArrayList<>(map.size());
            for (MetricAccumulator metric : map.values()) {
                result.add(new AggregateMetric(metric.id, metric.kind, metric.context.registryOwner(),
                    metric.context.rendererClass(), metric.context.rendererOwner(), metric.statistics(frames)));
            }
            result.sort(Comparator.comparingDouble((AggregateMetric m) -> m.stats().averageMs()).reversed()
                .thenComparing(AggregateMetric::id));
            return List.copyOf(result);
        }

        List<InstanceMetric> instances() {
            List<InstanceMetric> result = new ArrayList<>(instances.size());
            for (MetricAccumulator metric : instances.values()) {
                result.add(new InstanceMetric(metric.context, metric.statistics(frames), metric.lastSeen));
            }
            result.sort(Comparator.comparingDouble((InstanceMetric m) -> m.stats().averageMs()).reversed()
                .thenComparing(m -> m.context().key().identity()));
            return List.copyOf(result);
        }
    }

    /** Exact values in short rolling windows; ~2.2% logarithmic buckets in unbounded captures. */
    private static final class Distribution {
        private static final double LOG_BASE = Math.log(1.022);
        final TreeMap<Long, Long> values;
        final TreeMap<Integer, Long> histogram;
        long count;

        Distribution(boolean exact) {
            values = exact ? new TreeMap<>() : null;
            histogram = exact ? null : new TreeMap<>();
        }

        void add(long value) {
            if (values != null) values.merge(value, 1L, Long::sum);
            else histogram.merge(bucket(value), 1L, Long::sum);
            count++;
        }

        void remove(long value) {
            if (values == null) throw new IllegalStateException("Capture histograms cannot expire values");
            Long occurrences = values.get(value);
            if (occurrences == null) return;
            if (occurrences == 1) values.remove(value);
            else values.put(value, occurrences - 1);
            count--;
        }

        boolean isExact() { return values != null; }

        double maximum() { return values == null || values.isEmpty() ? 0 : values.lastKey(); }

        double percentile(double quantile, int totalFrames) {
            if (totalFrames <= 0) return 0;
            long rank = Math.max(1, (long) Math.ceil(totalFrames * quantile));
            long zeros = Math.max(0, totalFrames - count);
            if (rank <= zeros) return 0;
            rank -= zeros;
            if (values != null) {
                for (Map.Entry<Long, Long> entry : values.entrySet()) {
                    rank -= entry.getValue();
                    if (rank <= 0) return entry.getKey();
                }
                return 0;
            }
            for (Map.Entry<Integer, Long> entry : histogram.entrySet()) {
                rank -= entry.getValue();
                if (rank <= 0) return unbucket(entry.getKey());
            }
            return 0;
        }

        double meanSlowestFraction(double fraction, int totalFrames) {
            if (count == 0 || totalFrames == 0) return 0;
            long wanted = Math.max(1, (long) Math.ceil(totalFrames * fraction));
            long remaining = wanted;
            double sum = 0;
            if (values != null) {
                for (Map.Entry<Long, Long> entry : values.descendingMap().entrySet()) {
                    long take = Math.min(remaining, entry.getValue());
                    sum += entry.getKey() * (double) take;
                    remaining -= take;
                    if (remaining == 0) break;
                }
            } else {
                for (Map.Entry<Integer, Long> entry : histogram.descendingMap().entrySet()) {
                    long take = Math.min(remaining, entry.getValue());
                    sum += unbucket(entry.getKey()) * take;
                    remaining -= take;
                    if (remaining == 0) break;
                }
            }
            return sum / wanted;
        }

        private static int bucket(long value) {
            return value <= 0 ? 0 : 1 + (int) Math.floor(Math.log(value) / LOG_BASE);
        }

        private static double unbucket(int bucket) {
            return bucket <= 0 ? 0 : Math.exp((bucket - 0.5) * LOG_BASE);
        }
    }

    private static final class Capture {
        final long started;
        final long epochMillis;
        final int durationSeconds;
        final long initialDropped;
        final Aggregation aggregate = new Aggregation(false);
        final List<FramePoint> timeline = new ArrayList<>();
        long timelineStride = TIMELINE_INTERVAL;

        Capture(long started, long epochMillis, int durationSeconds, long initialDropped) {
            this.started = started;
            this.epochMillis = epochMillis;
            this.durationSeconds = durationSeconds;
            this.initialDropped = initialDropped;
        }

        boolean acceptsTimeline(Frame frame) {
            if (timeline.isEmpty()) return true;
            FramePoint previous = timeline.get(timeline.size() - 1);
            return Math.floorDiv(previous.timestampNanos() - started, timelineStride)
                != Math.floorDiv(frame.ended - started, timelineStride)
                || frame.spike && !previous.spike()
                || frame.spike == previous.spike() && ms(frame.duration) > previous.frameMs();
        }

        void appendTimeline(FramePoint point) {
            if (!timeline.isEmpty()) {
                FramePoint previous = timeline.get(timeline.size() - 1);
                if (Math.floorDiv(previous.timestampNanos() - started, timelineStride)
                    == Math.floorDiv(point.timestampNanos() - started, timelineStride)) {
                    if (previous.spike() && !point.spike()
                        || previous.spike() == point.spike() && previous.frameMs() >= point.frameMs()) return;
                    timeline.set(timeline.size() - 1, point);
                    return;
                }
            }
            timeline.add(point);
            if (timeline.size() >= 1024) {
                List<FramePoint> reduced = new ArrayList<>(512);
                for (int i = 0; i < timeline.size(); i += 2) {
                    FramePoint a = timeline.get(i);
                    FramePoint b = i + 1 < timeline.size() ? timeline.get(i + 1) : a;
                    reduced.add(a.spike() != b.spike() ? (a.spike() ? a : b)
                        : a.frameMs() >= b.frameMs() ? a : b);
                }
                timeline.clear();
                timeline.addAll(reduced);
                timelineStride *= 2;
            }
        }
    }

    private static Statistics singleFrameStats(CallSample sample) {
        return new Statistics(ms(sample.wall), ms(sample.wall), ms(sample.wall), ms(sample.cpu),
            sample.calls, sample.calls, sample.failures, sample.vertices, sample.bytes, 1, sample.renderTypes);
    }

    private static void addTypes(Set<String> destination, Set<String> source) {
        if (source == null) return;
        for (String type : source) {
            if (destination.size() >= MAX_RENDER_TYPES) break;
            if (type != null) destination.add(type);
        }
    }

    private static double ms(double nanos) { return nanos / 1_000_000.0; }
    private static double seconds(long nanos) { return Math.max(0, nanos) / (double) NS_PER_SECOND; }
    private static long saturatedAdd(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }
}
