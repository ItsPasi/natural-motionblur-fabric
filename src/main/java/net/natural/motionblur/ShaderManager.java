package net.natural.motionblur;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.config.ConfigEntries;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.ladysnake.satin.api.managed.ManagedShaderEffect;
import org.ladysnake.satin.api.managed.ShaderEffectManager;

public class ShaderManager {
    private static long lastNano;
    private static float currentBlur = 0.0f;
    private static float currentFPS = 0.0f;
    private static int sampleAmount = 100;

    private static final ManagedShaderEffect motionBlurShader = ShaderEffectManager.getInstance().manage(
            NaturalMotionBlurMod.createIdentifier("motion_blur"),
            shader -> shader.setUniformValue("BlendFactor", ConfigManager.getConfig().motionBlurStrength)
    );

    // Render Layer Hook
    public static void registerShaderCallbacks() {
        WorldRenderEvents.END.register((WorldRenderContext ctx) -> {
            long now = System.nanoTime();
            float deltaTime = (now - lastNano) / 1_000_000_000.0f;
            float deltaTick = deltaTime * 20.0f;
            lastNano = now;

            // FPS calculation
            if (deltaTime > 0 && deltaTime < 1.0f) {
                currentFPS = 1.0f / deltaTime;
            } else {
                currentFPS = 0.0f; // Avoid division by zero
            }

            if (shouldRenderMotionBlur()) {
                applyMotionBlur(deltaTick);
            }
        });
    }

    // Checks if blur should be rendered
    private static boolean shouldRenderMotionBlur() {
        ConfigEntries config = ConfigManager.getConfig();
        // Config enabled?
        if (config.motionBlurStrength == 0 || !config.enabled) {
            return false;
        }
        // Iris enabled?
        if (!IrisCheck.checkIrisShouldDisable()) {
            return false;
        }
        // F5 enabled?
        MinecraftClient client = MinecraftClient.getInstance();
        return client.options.getPerspective().isFirstPerson() || config.renderF5;
    }

    private static void applyMotionBlur(float deltaTick) {
        ConfigEntries config = ConfigManager.getConfig();
        MinecraftClient client = MinecraftClient.getInstance();

        // Detect refresh rate on first use
        MonitorInfoProvider.updateDisplayInfo();
        int displayRefreshRate = MonitorInfoProvider.getRefreshRate();

        // Scale blur based on FPS vs refresh rate
        float baseStrength = config.motionBlurStrength;
        float scaledStrength = baseStrength;
        if (config.useRefreshRateScaling) {
            float fpsOverRefresh = (displayRefreshRate > 0) ? currentFPS / displayRefreshRate : 1.0f;
            if (fpsOverRefresh < 1.0f) fpsOverRefresh = 1.0f; // don't weaken blur under refresh rate
            scaledStrength = baseStrength * fpsOverRefresh;

            // Scale sample amount proportionally when FPS exceeds refresh rate
            if (fpsOverRefresh > 1.0f) {
                sampleAmount = (int) (100 * fpsOverRefresh);
            }
        }

        // Update strength if changed
        if (currentBlur != scaledStrength) {
            motionBlurShader.setUniformValue("BlendFactor", scaledStrength);
            currentBlur = scaledStrength;
        }

        // Set uniform values for the shader
        motionBlurShader.setUniformValue("view_res", (float) client.getFramebuffer().viewportWidth, (float) client.getFramebuffer().viewportHeight);
        motionBlurShader.setUniformValue("view_pixel_size", 1.0f / client.getFramebuffer().viewportWidth, 1.0f / client.getFramebuffer().viewportHeight);
        motionBlurShader.setUniformValue("motionBlurSamples", sampleAmount);
        motionBlurShader.setUniformValue("blurAlgorithm", config.blurAlgorithm.ordinal());
        motionBlurShader.setUniformValue("useDepth", config.useDepthBasedBlur ? 1 : 0);

        // Render the shader effect
        motionBlurShader.render(deltaTick); // SatinAPI's render method expects deltaTick
    }

    private static final Matrix4f tempModelView = new Matrix4f();
    private static final Matrix4f tempPrevModelView = new Matrix4f();
    private static final Matrix4f tempProjection = new Matrix4f();
    private static final Matrix4f tempPrevProjection = new Matrix4f();
    private static final Matrix4f tempProjInverse = new Matrix4f();
    private static final Matrix4f tempMvInverse = new Matrix4f();

    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView,
                                          Matrix4f projection, Matrix4f prevProjection,
                                          Vector3f cameraPos, Vector3f prevCameraPos) {
        motionBlurShader.setUniformValue("modelView", tempModelView.set(modelView));
        motionBlurShader.setUniformValue("prevModelView", tempPrevModelView.set(prevModelView));
        motionBlurShader.setUniformValue("projection", tempProjection.set(projection));
        motionBlurShader.setUniformValue("prevProjection", tempPrevProjection.set(prevProjection));
        motionBlurShader.setUniformValue("projInverse", tempProjInverse.set(projection).invert());
        motionBlurShader.setUniformValue("mvInverse", tempMvInverse.set(modelView).invert());
        motionBlurShader.setUniformValue("cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);
        motionBlurShader.setUniformValue("prevCameraPos", prevCameraPos.x, prevCameraPos.y, prevCameraPos.z);
    }

    public static void updateBlurStrength(float strength) {
        motionBlurShader.setUniformValue("BlendFactor", strength);
        currentBlur = strength;
    }
}