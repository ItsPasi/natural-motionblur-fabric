package net.natural.motionblur.config;

public class ConfigEntries {
    public boolean enabled = true;
    public boolean refreshRateScaling = true;
    public float motionBlurStrength = 1.0F;
    public enum ExcludeEntities {ALWAYS, THIRD_PERSON, NEVER}
    public ExcludeEntities excludeEntities = ExcludeEntities.THIRD_PERSON;
    public enum BlurAlgorithm {BACKWARDS, CENTERED}
    public BlurAlgorithm blurAlgorithm = BlurAlgorithm.CENTERED;
}