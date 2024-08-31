package net.natural.motionblur.config;

import net.minecraft.client.util.InputUtil;
import net.natural.motionblur.MotionBlurMod;

public class MotionBlurConfig {

    public float motionBlurStrength = 1.0F;
    public int motionBlurSamples = 20;
    public MotionBlurMod.BlurAlgorithm blurAlgorithm = MotionBlurMod.BlurAlgorithm.CENTERED;
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