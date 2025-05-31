package net.natural.motionblur.config;

import net.minecraft.client.util.InputUtil;

public class ConfigEntries {
    public float motionBlurStrength = 1.0F;
    public enum MotionBlurSamples {LOW(2), MEDIUM(10), HIGH(20);
        private final int value;
        MotionBlurSamples(int value) {this.value = value;}
        public int getValue() {return this.value;}};
    public MotionBlurSamples motionBlurSamples = MotionBlurSamples.MEDIUM;
    public enum BlurAlgorithm {BACKWARDS, CENTERED}
    public BlurAlgorithm blurAlgorithm = BlurAlgorithm.CENTERED;
    public boolean renderF5 = true;
    public boolean enabled = true;
    public String toggleKey = "key.keyboard.v";

    public InputUtil.Key getToggleKey() {
        return InputUtil.fromTranslationKey(toggleKey);
    }

    public void setToggleKey(InputUtil.Key key) {
        this.toggleKey = key.getTranslationKey();
    }
}