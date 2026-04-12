package net.natural.motionblur.config;

public class ConfigEntries {
    public boolean enabled = true;
    public boolean refreshRateScaling = true;
    public float motionBlurStrength = 1.0F;

    public transient boolean recordingOverlayEnabled = false;
    public int recordingOverlayTargetFPS = 60;

    public enum BlurAlgorithm {VELOCITY_BASED, FRAME_BLENDING, ACCUMULATION_MAX, ACCUMULATION_MIX}
    public BlurAlgorithm blurAlgorithm = BlurAlgorithm.VELOCITY_BASED;
}