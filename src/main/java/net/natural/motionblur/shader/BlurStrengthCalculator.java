package net.natural.motionblur.shader;

public class BlurStrengthCalculator {

    public static class Result {
        public final float strength;
        public final int   sampleAmount;

        Result(float strength, int sampleAmount) {
            this.strength     = strength;
            this.sampleAmount = sampleAmount;
        }
    }

    public Result calculate(float baseStrength, float fps, int refreshRate, boolean scalingEnabled) {
        if (!scalingEnabled) {
            return new Result(baseStrength, 100);
        }

        // Ratio of current FPS to the display's refresh rate.
        float fpsOverRefresh = (refreshRate > 0) ? fps / refreshRate : 1.0f;
        if (fpsOverRefresh < 1.0f) fpsOverRefresh = 1.0f;

        float scaledStrength = baseStrength * fpsOverRefresh;

        // Scale sample count proportionally when FPS exceeds the refresh rate,
        int sampleAmount = (fpsOverRefresh > 1.0f) ? (int) (100 * fpsOverRefresh) : 100;

        return new Result(scaledStrength, sampleAmount);
    }
}
