package net.natural.motionblur;

import net.minecraft.client.MinecraftClient;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.config.ConfigEntries;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ladysnake.satin.api.event.PostWorldRenderCallbackV2;
import ladysnake.satin.api.managed.ManagedShaderEffect;
import ladysnake.satin.api.managed.ShaderEffectManager;

public class ShaderManager {
    private static long lastNano;
    private static float currentBlur = 0.0f;
    private static float currentFPS = 0.0f;

    private static final ManagedShaderEffect motionBlurShader = ShaderEffectManager.getInstance().manage(
            NaturalMotionBlurMod.createIdentifier("shaders/post/motion_blur.json"),
            shader -> shader.setUniformValue("BlendFactor", ConfigManager.getConfig().motionBlurStrength)
    );

    // Render Layer Hook
    public static void registerShaderCallbacks() {
        PostWorldRenderCallbackV2.EVENT.register((matrix, camera, deltaTick, a) -> {
            long now = System.nanoTime();
            float deltaTime = (now - lastNano) / 1_000_000_000.0f;
            lastNano = now;

            if (deltaTime > 0) {
                currentFPS = 1.0f / deltaTime;
            } else {
                currentFPS = 0.0f; // Avoid division by zero
            }

            if (shouldRenderMotionBlur()) {
                renderMotionBlur(deltaTick);
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

    private static void renderMotionBlur(float deltaTick) {
        ConfigEntries config = ConfigManager.getConfig();
        MinecraftClient client = MinecraftClient.getInstance();

        // Update strength if changed
        if (currentBlur != config.motionBlurStrength) {
            motionBlurShader.setUniformValue("BlendFactor", config.motionBlurStrength);
            currentBlur = config.motionBlurStrength;
        }

        // Determine sample amount based on FPS
        int sampleAmount = getSampleAmountForFPS(currentFPS);
        int halfSampleAmount = sampleAmount / 2;
        float invSamples = 1.0f / sampleAmount;

        // Set uniform values for the shader
        motionBlurShader.setUniformValue("view_res", (float) client.getFramebuffer().viewportWidth, (float) client.getFramebuffer().viewportHeight);
        motionBlurShader.setUniformValue("view_pixel_size", 1.0f / client.getFramebuffer().viewportWidth, 1.0f / client.getFramebuffer().viewportHeight);
        motionBlurShader.setUniformValue("motionBlurSamples", sampleAmount);
        motionBlurShader.setUniformValue("halfSamples", halfSampleAmount);
        motionBlurShader.setUniformValue("inverseSamples", invSamples);
        motionBlurShader.setUniformValue("blurAlgorithm", config.blurAlgorithm.ordinal());

        // Render the shader effect
        motionBlurShader.render(deltaTick); // SatinAPI's render method expects deltaTick
    }

    // Determine sample amount based on FPS
    private static int getSampleAmountForFPS(float fps) {
        if (fps > 360) {return 8;}
        else if (fps > 120) {return 10;}
        else if (fps > 60) {return 12;}
        else {return 20;}
    }

    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView,
                                          Matrix4f projection, Matrix4f prevProjection,
                                          Vector3f cameraPos, Vector3f prevCameraPos) {
        motionBlurShader.setUniformValue("modelView", new Matrix4f(modelView));
        motionBlurShader.setUniformValue("prevModelView", new Matrix4f(prevModelView));
        motionBlurShader.setUniformValue("projection", new Matrix4f(projection));
        motionBlurShader.setUniformValue("prevProjection", new Matrix4f(prevProjection));
        motionBlurShader.setUniformValue("projInverse", new Matrix4f(projection).invert());
        motionBlurShader.setUniformValue("mvInverse", new Matrix4f(modelView).invert());
        motionBlurShader.setUniformValue("cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);
        motionBlurShader.setUniformValue("prevCameraPos", prevCameraPos.x, prevCameraPos.y, prevCameraPos.z);
    }

    public static void updateBlurStrength(float strength) {
        motionBlurShader.setUniformValue("BlendFactor", strength);
        currentBlur = strength;
    }
}