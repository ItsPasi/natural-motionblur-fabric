package net.natural.motionblur.shader;

import org.joml.Matrix4f;

public class CameraState {

    private final Matrix4f mvInverse   = new Matrix4f();
    private final Matrix4f projInverse = new Matrix4f();
    private final Matrix4f prevModelView  = new Matrix4f();
    private final Matrix4f prevProjection = new Matrix4f();
    private float dx, dy, dz;

    // Scratch matrix used only during inversion — avoids allocating a new one each frame
    private final Matrix4f scratch = new Matrix4f();

    public void setFrame(Matrix4f modelView, Matrix4f prevModelView,
                         Matrix4f projection, Matrix4f prevProjection,
                         float dx, float dy, float dz) {
        this.mvInverse.set(scratch.set(modelView).invert());
        this.projInverse.set(scratch.set(projection).invert());
        this.prevModelView.set(prevModelView);
        this.prevProjection.set(prevProjection);
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    // Getters (read-only views for UBO writing)
    public Matrix4f getMvInverse()      { return mvInverse; }
    public Matrix4f getProjInverse()    { return projInverse; }
    public Matrix4f getPrevModelView()  { return prevModelView; }
    public Matrix4f getPrevProjection() { return prevProjection; }
    public float getDx() { return dx; }
    public float getDy() { return dy; }
    public float getDz() { return dz; }
}
