package dev.renderscope.core;

public record InstanceMetric(SampleContext context, Statistics stats, long lastSeenNanos) {}
