package cn.noryea.motionblur.config;

import cn.noryea.motionblur.MotionBlurMod;
import com.mojang.datafixers.util.Pair;

public class MotionBlurConfig {

    public static SimpleConfig CONFIG;
    private static MotionBlurConfigProvider provider;

    public static float MOTIONBLUR_AMOUNT;  //是动态模糊量

    public static void registerConfigs(float amount) {
        provider = new MotionBlurConfigProvider();

        provider.addKeyValuePair(new Pair<>("motionblur.amount", amount), "float");
        CONFIG = SimpleConfig.of(MotionBlurMod.ID).provider(provider).request();

        syncConfigs();
    }

    private static void syncConfigs() {
        MOTIONBLUR_AMOUNT = CONFIG.getOrDefault("motionblur.amount", 1.0f);

        //System.out.println(provider.getConfigsList().size() + " have been set.");
    }

    public static void update(float value) {
        CONFIG.delete();
        registerConfigs(value);
    }

}
