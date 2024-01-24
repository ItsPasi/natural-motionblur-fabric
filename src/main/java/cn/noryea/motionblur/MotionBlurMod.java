package cn.noryea.motionblur;

import cn.noryea.motionblur.config.MotionBlurConfig;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.sun.jna.platform.linux.LibC;
import ladysnake.satin.api.event.PostWorldRenderCallbackV2;
import ladysnake.satin.api.event.ShaderEffectRenderCallback;
import ladysnake.satin.api.managed.ManagedShaderEffect;
import ladysnake.satin.api.managed.ShaderEffectManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.Configuration;

import static java.lang.Thread.sleep;

public class MotionBlurMod implements ClientModInitializer {

    public static String ID = "motionblur";
    private float currentBlur;
    private float cachedWidth;
    private float cachedHeight;

    private static final ManagedShaderEffect motionblur = ShaderEffectManager.getInstance().manage(new Identifier(ID, "shaders/post/motion_blur.json"),
            shader -> shader.setUniformValue("BlendFactor", getBlur()));

    @Override
    public void onInitializeClient() {
        MotionBlurConfig.registerConfigs(1.0f);

        ClientCommandRegistrationCallback.EVENT.register((callback, a) -> {
            callback.register(
                    ClientCommandManager.literal("motionblur")
                            .then(ClientCommandManager.argument("percent", FloatArgumentType.floatArg(0.0f, 10.0f))
                                    .executes(context -> changeAmount(context.getSource(), FloatArgumentType.getFloat(context, "percent"))))
            );
        });

        PostWorldRenderCallbackV2.EVENT.register((matrix, camera, deltaTick, a) -> {

            if (getBlur() != 0) {
                if(currentBlur!=getBlur()){
                    motionblur.setUniformValue("BlendFactor", getBlur());
                    currentBlur=getBlur();
                }
                motionblur.setUniformValue("view_res", (float) MinecraftClient.getInstance().getFramebuffer().viewportWidth, (float) MinecraftClient.getInstance().getFramebuffer().viewportHeight);
                motionblur.setUniformValue("view_pixel_size", 1.0f / MinecraftClient.getInstance().getFramebuffer().viewportWidth, 1.0f / MinecraftClient.getInstance().getFramebuffer().viewportHeight);
                if (!Screen.hasShiftDown()) {
                    motionblur.render(deltaTick);
                }
            }
        });
    }

    private static int changeAmount(FabricClientCommandSource src, float amount) {
        MotionBlurConfig.update(amount);
        //MotionBlurConfig.MOTIONBLUR_AMOUNT = amount;

        src.sendFeedback(Text.of("Motion Blur: " + amount + "%"));
        return 1;

    }

    public static float getBlur() {
        return MotionBlurConfig.MOTIONBLUR_AMOUNT;
    }

    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView, Matrix4f projection, Matrix4f prevProjection, Vector3f cameraPos, Vector3f prevCameraPos) {
        motionblur.setUniformValue("modelView", new Matrix4f(modelView));
        motionblur.setUniformValue("prevModelView", new Matrix4f(prevModelView));
        motionblur.setUniformValue("projection", new Matrix4f(projection));
        motionblur.setUniformValue("prevProjection", new Matrix4f(prevProjection));
        motionblur.setUniformValue("projInverse", new Matrix4f(projection).invert());
        motionblur.setUniformValue("mvInverse", new Matrix4f(modelView).invert());
        motionblur.setUniformValue("cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);
        motionblur.setUniformValue("prevCameraPos", prevCameraPos.x, prevCameraPos.y, prevCameraPos.z);
    }
}
