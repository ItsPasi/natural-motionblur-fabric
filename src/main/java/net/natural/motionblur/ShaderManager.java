package net.natural.motionblur;

import net.minecraft.client.MinecraftClient;
import net.natural.motionblur.config.ConfigManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.ladysnake.satin.api.event.PostWorldRenderCallbackV2;
import org.ladysnake.satin.api.managed.ManagedShaderEffect;
import org.ladysnake.satin.api.managed.ShaderEffectManager;

public class ShaderManager {
    private static float currentBlur = 0.0f;

    private static final ManagedShaderEffect motionBlurShader = ShaderEffectManager.getInstance().manage(
            NaturalMotionBlur.createIdentifier("shaders/post/motion_blur.json"),
            shader -> shader.setUniformValue("BlendFactor", ConfigManager.getConfig().motionBlurStrength)
    );

    // Render Layer Hook
    public static void registerShaderCallbacks() {
        PostWorldRenderCallbackV2.EVENT.register((matrix, camera, deltaTick) -> {
            if (shouldRenderMotionBlur()) {
                renderMotionBlur(deltaTick);
            }
        });
    }

    private static boolean shouldRenderMotionBlur() {
        net.natural.motionblur.config.MotionBlurConfig config = ConfigManager.getConfig();
        if (config.motionBlurStrength == 0 || !config.enabled) {
            return false;
        }

        if (!IrisCheck.checkIrisShouldDisable()) {
            return false;
        }

        // Check if in third person and if third person rendering is disabled
        MinecraftClient client = MinecraftClient.getInstance();
        return client.options.getPerspective().isFirstPerson() || config.renderF5;
    }

    private static void renderMotionBlur(float deltaTick) {
        net.natural.motionblur.config.MotionBlurConfig config = ConfigManager.getConfig();
        MinecraftClient client = MinecraftClient.getInstance();

        // Update strength if changed
        if (currentBlur != config.motionBlurStrength) {
            motionBlurShader.setUniformValue("BlendFactor", config.motionBlurStrength);
            currentBlur = config.motionBlurStrength;
        }

        // Set uniform values for the shader
        motionBlurShader.setUniformValue("view_res",
                (float) client.getFramebuffer().viewportWidth,
                (float) client.getFramebuffer().viewportHeight
        );

        motionBlurShader.setUniformValue("view_pixel_size",
                1.0f / client.getFramebuffer().viewportWidth,
                1.0f / client.getFramebuffer().viewportHeight
        );

        motionBlurShader.setUniformValue("motionBlurSamples", config.motionBlurSamples);
        motionBlurShader.setUniformValue("blurAlgorithm", config.blurAlgorithm.ordinal());

        // Render the shader effect
        motionBlurShader.render(deltaTick);
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