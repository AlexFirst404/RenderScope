# RenderScope profiler core API

All classes below are `dev.renderscope.core`, Java 17 compatible, with no Minecraft dependencies. Times exposed to the UI are milliseconds. All snapshots and their collections are immutable. `RenderProfiler.snapshot()` is cached for 250 ms; the profiler continues collecting while a screen is open or closed.

```java
enum ObjectKind { ENTITY, BLOCK_ENTITY }
record InstanceKey(ObjectKind kind, String dimension, String identity) {}
record Position(double x, double y, double z) {}
record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
record SampleContext(InstanceKey key, String registryId, String registryOwner,
                     String rendererClass, String rendererOwner, Position position, Bounds bounds) {}
record Statistics(double averageMs, double p95Ms, double peakMs, double cpuMs,
                  double callsPerFrame, long calls, long failures,
                  double verticesPerFrame, double estimatedBytesPerFrame,
                  int instances, Set<String> renderTypes) {}
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
                        long droppedMeasurements) {}
record CaptureReport(long startedAtEpochMillis, double durationSeconds, int frames,
                     Performance performance, List<AggregateMetric> mods,
                     List<AggregateMetric> renderers, List<AggregateMetric> objects,
                     List<InstanceMetric> instances, List<FramePoint> timeline,
                     long droppedMeasurements) {}

RenderProfiler profiler = new RenderProfiler();
profiler.beginFrame();
RenderProfiler.CallToken token = profiler.beginCall(context); // nullable when disabled
profiler.endCall(token, vertices, estimatedBytes, renderTypes, failure); // failure: boolean
profiler.excludeNestedCall(parentToken, completedChildToken); // after child ends, before parent ends
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
