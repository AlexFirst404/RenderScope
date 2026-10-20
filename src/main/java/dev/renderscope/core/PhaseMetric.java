package dev.renderscope.core;

/** Shared CPU phase kept outside object/mod cost: no artificial world instance or owner. */
public record PhaseMetric(String id, Statistics stats) { }
