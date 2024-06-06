package net.natural.motionblur.config;


import org.lwjgl.glfw.GLFW;

public class MotionBlurConfig {

    public float motionBlurStrength = 1.0F;
    public int motionBlurSamples = 20;
    public boolean renderF5 = true;
    public boolean enabled = true;
    public int toggleKey = GLFW.GLFW_KEY_V;


}
