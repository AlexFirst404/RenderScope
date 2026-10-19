package dev.renderscope.core;

/** Kind is null for aggregates containing both entity categories. */
public record AggregateMetric(String id, ObjectKind kind, String registryOwner,
                              String rendererClass, String rendererOwner, Statistics stats) {}
