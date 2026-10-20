# RenderScope profiler core API

All classes below are `dev.renderscope.core`, Java 17 compatible, with no Minecraft dependencies. Times exposed to the UI are milliseconds. All snapshots and their collections are immutable. `RenderProfiler.snapshot()` is cached for 250 ms; the profiler continues collecting while a screen is open or closed.

```java
enum ObjectKind { ENTITY, BLOCK_ENTITY }
record InstanceKey(ObjectKind kind, String dimension, String identity) {}
record Position(double x, double y, double z) {}
record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
record SampleContext(InstanceKey key, String registryId, String registryOwner,
                     String rendererClass, String rendererOwner, Position position, Bounds bounds) {}
enum MeasurementSource {
    RENDERER, FLYWHEEL_VISUAL_CREATE, FLYWHEEL_VISUAL_FRAME, FLYWHEEL_VISUAL_TICK,
    FLYWHEEL_VISUAL_UPDATE, FLYWHEEL_VISUAL_LIGHT, FLYWHEEL_BATCH_SUBMIT
}
record SourceMetric(double averageMs, double cpuMs, double callsPerFrame, long calls, long failures) {}
record Statistics(double averageMs, double p95Ms, double peakMs, double cpuMs,
                  double callsPerFrame, long calls, long failures,
                  double verticesPerFrame, double estimatedBytesPerFrame,
                  int instances, Set<String> renderTypes,
                  Map<MeasurementSource, SourceMetric> sources) {}
record PhaseMetric(String id, Statistics stats) {}
record AggregateMetric(String id, ObjectKind kind, String registryOwner,
                       String rendererClass, String rendererOwner, Statistics stats) {}
record InstanceMetric(SampleContext context, Statistics stats, long lastSeenNanos) {}
record Performance(int frames, double fps, double currentFrameMs, double averageFrameMs,
                   double onePercentLowFps, double p50FrameMs, double p95FrameMs,
                   double p99FrameMs, double worstFrameMs, double measuredRenderMs,
                   double entityRenderMs, double blockEntityRenderMs,
                   double rendererCallsPerFrame, double renderedEntitiesPerFrame,
                   double renderedBlockEntitiesPerFrame, int measuredObjects,
                   double verticesPerFrame, double estimatedBytesPerFrame) {}
record FramePoint(long timestampNanos, double frameMs, double measuredRenderMs,
                  double entityRenderMs, double blockEntityRenderMs, boolean spike,
                  Map<String, Double> topModMs, InstanceMetric worstInstance) {}
record CaptureStatus(boolean active, double elapsedSeconds, int durationSeconds) {}
record ProfilerSnapshot(boolean enabled, double windowSeconds, Performance performance,
                        List<AggregateMetric> mods, List<AggregateMetric> renderers,
                        List<AggregateMetric> objects, List<InstanceMetric> instances,
                        List<FramePoint> timeline, CaptureStatus capture,
                        long droppedMeasurements, List<PhaseMetric> phases) {}
record CaptureReport(long startedAtEpochMillis, double durationSeconds, int frames,
                     Performance performance, List<AggregateMetric> mods,
                     List<AggregateMetric> renderers, List<AggregateMetric> objects,
                     List<InstanceMetric> instances, List<FramePoint> timeline,
                     long droppedMeasurements, List<PhaseMetric> phases) {}

RenderProfiler profiler = new RenderProfiler();
profiler.beginFrame();
RenderProfiler.CallToken token = profiler.beginCall(context); // nullable when disabled
profiler.endCall(token, vertices, estimatedBytes, renderTypes, failure); // failure: boolean
profiler.excludeNestedCall(parentToken, completedChildToken); // after child ends, before parent ends
RenderProfiler.FrameHandle scheduledFrame = profiler.currentFrame(); // capture before enqueueing work
RenderProfiler.CallToken task = profiler.beginCall(context, MeasurementSource.FLYWHEEL_VISUAL_FRAME, scheduledFrame);
// begin/end the call on the same worker; stale frames/world sessions are rejected
RenderProfiler.ExcludedInterval continuation = profiler.beginExcludedInterval(task);
// run a synchronous completion callback owned by a different plan, outside this task's CPU scope
profiler.endExcludedInterval(continuation);
RenderProfiler.PhaseToken phase = profiler.beginPhase("flywheel:batch_submit", MeasurementSource.FLYWHEEL_BATCH_SUBMIT);
profiler.endPhase(phase, false); // separate shared CPU phase, no synthetic instance/mod
profiler.endFrame();
profiler.endFrame(frameNanos); // overload when adapter knows the whole frame duration
ProfilerSnapshot snapshot = profiler.snapshot();
profiler.setEnabled(boolean);
profiler.setCpuTimingEnabled(boolean); // default false, optional ThreadMXBean timing
profiler.setSampleWindowSeconds(int); // 1 through 30, default 5
profiler.startCapture(int); // 5,10,30; 0 = manual; resets previous completed capture
CaptureReport report = profiler.stopCapture(); // null if no capture exists
CaptureReport report = profiler.lastCapture(); // null until capture has completed
profiler.clear(); // world/dimension session change; stops capture, releases all contexts
```

Lists are sorted by descending average render time. Mods use registry namespace as `id`; renderer metrics use renderer class name; object metrics use registry id plus separate `kind`. The adapter resolves display names through the loader. Instance identities are opaque (entity numeric ID or block coordinate string); do not display or export identity or dimension by default. Core retains only value records, never game object references. Per-frame averages and percentiles include zero for frames in which the key did not render. The adapter calls `excludeNestedCall(parent, child)` after completing a nested call to keep timings exclusive. Exclusion uses the child's raw inclusive interval, so grandchildren are removed exactly once from every ancestor; repeated, stale and cross-thread exclusions are ignored. Capture statistics cover every frame from capture start until completion, independent of the rolling window. Capture percentiles use a bounded logarithmic histogram; rolling percentiles are exact. Vertices and byte metrics are estimates. No field represents GPU time or VRAM.

The rolling data, timeline, tracked keys and per-frame events have hard bounds. A frame tracks at most 4,096 instances. Rolling history retains at most 8,192 frames and 262,144 instance/group samples. Manual captures retain at most 4,096 keys per aggregate category and at most 1,024 timeline points, downsampling older intervals as necessary; every frame still contributes to global totals. `droppedMeasurements` reports truncation if a bound is reached. `FramePoint` stores real spike attribution; timeline points are downsampled to ten per second, preserving spikes first and otherwise the slowest frame of each interval. Core caps retained history at 30 seconds. Rolling statistics update incrementally as frames enter and leave the window, so snapshot creation does not rescan raw calls.

Source totals identify the actual work observed. A world's instance can combine renderer and visual callbacks; each renderer implementation keeps its own aggregate rather than inheriting all time from the instance's latest context. Instance context describes the latest completed implementation. `sources` uses the same all-frame divisor as the parent statistics. Wall times summed across worker tasks describe executing CPU-side intervals and can exceed elapsed frame time when workers run concurrently. They are not asynchronous job latency or GPU time. Creation records the actual storage factory call, while callback records use the visual implementation. All classic constructors remain available for adapter/test compatibility.

`FrameHandle` is opaque and contains only profiler/session/frame identity. Adapters propagate the handle captured at scheduling through worker task trees. Calls from a closed frame, old world session or a different completion thread cannot mutate a later frame. `phases` stores shared work such as Flywheel batch submission separately; it does not increase mod, object, instance or measured-object renderer totals. A frame retains at most 64 phase IDs and 16,384 renderer/instance links. `WeakIdentityMap<V>` provides bounded, synchronized weak-key identity associations; its values must also avoid strong game-object references.
