package net.natural.motionblur.config;

public class ConfigEntries {
    public boolean enabled = true;
    public boolean refreshRateScaling = true;
    public float motionBlurStrength = 1.0F;

    public transient boolean recordingOverlayEnabled = false;
    public int recordingOverlayTargetFPS = 60;

    public enum BlurAlgorithm {VELOCITY_BASED, FRAME_BLENDING, HYBRID_BLENDING, ACCUMULATION_MAX, ACCUMULATION_MIX}
    public BlurAlgorithm blurAlgorithm = BlurAlgorithm.VELOCITY_BASED;

    public boolean usesVelocityBlur() {return blurAlgorithm == BlurAlgorithm.VELOCITY_BASED || blurAlgorithm == BlurAlgorithm.HYBRID_BLENDING;}
    public boolean allowsRefreshRateScaling() {return blurAlgorithm == BlurAlgorithm.VELOCITY_BASED;}
    public boolean locksStrengthToOne() {return blurAlgorithm == BlurAlgorithm.HYBRID_BLENDING;}
    public float getEffectiveMotionBlurStrength() {return locksStrengthToOne() ? 1.0F : motionBlurStrength;}
    public boolean showsStrengthSlider() {return blurAlgorithm != BlurAlgorithm.FRAME_BLENDING && !locksStrengthToOne();}
}