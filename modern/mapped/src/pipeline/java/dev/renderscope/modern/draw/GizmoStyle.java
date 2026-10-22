package dev.renderscope.modern.draw;

/** An actual native line outline, without asserting support for vanilla's later gizmo fill effect. */
public record GizmoStyle(int color) {
    public static GizmoStyle stroke(int color) { return new GizmoStyle(color); }
}
