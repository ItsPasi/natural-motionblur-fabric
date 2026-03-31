package net.natural.motionblur.config;

public class ConfigEntries {
    public boolean enabled = true;
    public boolean refreshRateScaling = true;
    public float motionBlurStrength = 1.0F;
    public enum BlurAlgorithm {BACKWARDS, CENTERED}
    public BlurAlgorithm blurAlgorithm = BlurAlgorithm.CENTERED;
}