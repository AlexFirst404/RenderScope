package dev.renderscope.modern.draw;

/** RenderScope's outline command properties for versions predating vanilla Gizmos. */
public final class GizmoProperties {
    boolean throughWalls;
    public GizmoProperties setAlwaysOnTop() { throughWalls = true; return this; }
}
