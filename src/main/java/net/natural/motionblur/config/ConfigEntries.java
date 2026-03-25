package net.natural.motionblur.config;

import com.mojang.blaze3d.platform.InputConstants;

public class ConfigEntries {
    public boolean enabled = true;
    public boolean renderF5 = true;
    public boolean refreshRateScaling = true;
    public boolean depthBlur = true;
    public float motionBlurStrength = 1.0F;
    public enum BlurAlgorithm {BACKWARDS, CENTERED}
    public BlurAlgorithm blurAlgorithm = BlurAlgorithm.CENTERED;
    public String toggleKey = "key.keyboard.b";

    public InputConstants.Key getToggleKey() {
        return InputConstants.getKey(toggleKey);
    }

    public void setToggleKey(InputConstants.Key key) {
        this.toggleKey = key.getName();
    }
}