package net.natural.motionblur.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting.Entry;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.recording.RecordingShaderManager;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;

import java.util.OptionalDouble;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Shadow @Final private GameRenderer gameRenderer;
    @Shadow @Final private OptionsRenderState optionsRenderState;
    @Shadow @Final private LevelTargetBundle targets;
    @Shadow private @Nullable GpuSampler chunkLayerSampler;
    @Shadow @Final private static Vector4fc ENTITY_OUTLINE_CLEAR_COLOR;

    @Unique private final Matrix4f prevModelView     = new Matrix4f();
    @Unique private final Matrix4f prevProjection    = new Matrix4f();
    @Unique private final Matrix4f scratchModelView  = new Matrix4f();
    @Unique private final Matrix4f scratchProjection = new Matrix4f();
    @Unique private double prevCamX, prevCamY, prevCamZ;
    @Unique private boolean previousFrameReady = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(
            GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
            boolean renderOutline, CameraRenderState cameraState,
            Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog,
            Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {

        ConfigEntries config = ConfigManager.getConfig();
        boolean blurActive = config.enabled && config.getEffectiveMotionBlurStrength() != 0.0f;
        boolean recordingActive = config.recordingOverlayEnabled;
        boolean needsVelocityState = blurActive && config.usesVelocityBlur();

        double cx = cameraState.pos.x();
        double cy = cameraState.pos.y();
        double cz = cameraState.pos.z();

        if (!blurActive && !recordingActive) {
            ShaderManager.clearFrameAllocator();
            naturalMotionBlur$rememberCurrentFrameState(modelViewMatrix, cameraState.projectionMatrix, cx, cy, cz);
            return;
        }

        if (blurActive) {
            ShaderManager.captureAllocator(resourceAllocator);
        } else {
            ShaderManager.clearFrameAllocator();
        }
        if (recordingActive) {
            RecordingShaderManager.captureAllocator(resourceAllocator);
        }
        ShaderManager.beginFrame();

        if (!needsVelocityState) {
            naturalMotionBlur$rememberCurrentFrameState(modelViewMatrix, cameraState.projectionMatrix, cx, cy, cz);
            return;
        }

        scratchModelView.set(modelViewMatrix);
        scratchProjection.set(cameraState.projectionMatrix);

        if (!previousFrameReady) {
            ShaderManager.setFrameMotionBlur(
                    scratchModelView, scratchModelView,
                    scratchProjection, scratchProjection,
                    0.0f, 0.0f, 0.0f);

            naturalMotionBlur$rememberCurrentFrameState(scratchModelView, scratchProjection, cx, cy, cz);
            return;
        }

        float dx = (float)(cx - prevCamX);
        float dy = (float)(cy - prevCamY);
        float dz = (float)(cz - prevCamZ);

        ShaderManager.setFrameMotionBlur(
                scratchModelView, prevModelView,
                scratchProjection, prevProjection,
                dx, dy, dz);

        naturalMotionBlur$rememberCurrentFrameState(scratchModelView, scratchProjection, cx, cy, cz);
    }


    @Unique
    private void naturalMotionBlur$rememberCurrentFrameState(Matrix4fc modelViewMatrix, Matrix4fc projectionMatrix, double cx, double cy, double cz) {
        prevModelView.set(modelViewMatrix);
        prevProjection.set(projectionMatrix);
        prevCamX = cx;
        prevCamY = cy;
        prevCamZ = cz;
        previousFrameReady = true;
    }

    // Apply post-entity blur
    @Inject(method = "render", at = @At("TAIL"))
    private void naturalMotionBlur$onRenderLevelTail(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
        ConfigEntries config = ConfigManager.getConfig();
        boolean specialSingleBlur = naturalMotionBlur$shouldUseSpecialSingleBlur();

        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.HYBRID_BLENDING) {
            if (!specialSingleBlur) {
                ShaderManager.applyPostRenderVelocityOnly();
            }
        } else if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.VELOCITY_BASED && !specialSingleBlur) {
            ShaderManager.applyPostRenderVelocityOnly();
        }
    }


    // Keep pre-entity blur inside the 26.2 frame graph main pass
    @Overwrite
    private void addMainPass(FrameGraphBuilder frame, PreparedFrame featureFrame, GpuBufferSlice terrainFog, LevelRenderState levelRenderState, ProfilerFiller profiler, ChunkSectionsToRender chunkSectionsToRender) {
        LevelTargetBundleAccessor targetAccess = (LevelTargetBundleAccessor) this.targets;

        FramePass terrainPass = frame.addPass("main");
        targetAccess.naturalMotionBlur$setMain(terrainPass.readsAndWrites(targetAccess.naturalMotionBlur$getMain()));
        terrainPass.executes(
                () -> {
                    RenderSystem.setShaderFog(terrainFog);
                    if (levelRenderState.shouldResetChunkLayerSampler || this.chunkLayerSampler == null) {
                        if (this.chunkLayerSampler != null) {
                            this.chunkLayerSampler.close();
                        }

                        int maxAnisotropy = this.optionsRenderState.textureFiltering == TextureFilteringMethod.ANISOTROPIC
                                ? this.optionsRenderState.maxAnisotropyValue
                                : 1;
                        this.chunkLayerSampler = RenderSystem.getDevice()
                                .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, maxAnisotropy, OptionalDouble.empty());
                    }

                    profiler.push("solidTerrain");
                    chunkSectionsToRender.renderGroup(ChunkSectionLayerGroup.OPAQUE, this.chunkLayerSampler);
                    profiler.pop();
                }
        );

        RenderTarget mainRenderTarget = ((GameRendererAccessor) this.gameRenderer).naturalMotionBlur$mainRenderTarget();
        ShaderManager.addPreEntityBlurToFrame(frame, mainRenderTarget.width, mainRenderTarget.height, this.targets, naturalMotionBlur$shouldUseSpecialSingleBlur());

        FramePass featurePass = frame.addPass("features");
        targetAccess.naturalMotionBlur$setMain(featurePass.readsAndWrites(targetAccess.naturalMotionBlur$getMain()));
        if (targetAccess.naturalMotionBlur$getTranslucent() != null) {
            targetAccess.naturalMotionBlur$setTranslucent(featurePass.readsAndWrites(targetAccess.naturalMotionBlur$getTranslucent()));
        }
        if (targetAccess.naturalMotionBlur$getItemEntity() != null) {
            targetAccess.naturalMotionBlur$setItemEntity(featurePass.readsAndWrites(targetAccess.naturalMotionBlur$getItemEntity()));
        }
        if (targetAccess.naturalMotionBlur$getWeather() != null) {
            targetAccess.naturalMotionBlur$setWeather(featurePass.readsAndWrites(targetAccess.naturalMotionBlur$getWeather()));
        }
        if (targetAccess.naturalMotionBlur$getParticles() != null) {
            targetAccess.naturalMotionBlur$setParticles(featurePass.readsAndWrites(targetAccess.naturalMotionBlur$getParticles()));
        }
        if (featureFrame.hasAnyOutline() && targetAccess.naturalMotionBlur$getEntityOutline() != null) {
            targetAccess.naturalMotionBlur$setEntityOutline(featurePass.readsAndWrites(targetAccess.naturalMotionBlur$getEntityOutline()));
        }

        ResourceHandle<RenderTarget> mainTarget = targetAccess.naturalMotionBlur$getMain();
        ResourceHandle<RenderTarget> translucentTarget = targetAccess.naturalMotionBlur$getTranslucent();
        ResourceHandle<RenderTarget> itemEntityTarget = targetAccess.naturalMotionBlur$getItemEntity();
        ResourceHandle<RenderTarget> entityOutlineTarget = targetAccess.naturalMotionBlur$getEntityOutline();
        ResourceHandle<RenderTarget> particleTarget = targetAccess.naturalMotionBlur$getParticles();

        featurePass.executes(
                () -> {
                    RenderSystem.setShaderFog(terrainFog);
                    this.gameRenderer.lighting().setupFor(Entry.LEVEL);
                    if (levelRenderState.shouldShowEntityOutlines && entityOutlineTarget != null) {
                        RenderTarget outlineTarget = entityOutlineTarget.get();
                        assert outlineTarget.getColorTexture() != null;
                        assert outlineTarget.getDepthTexture() != null;
                        RenderSystem.getDevice()
                                .createCommandEncoder()
                                .clearColorAndDepthTextures(outlineTarget.getColorTexture(), ENTITY_OUTLINE_CLEAR_COLOR, outlineTarget.getDepthTexture(), 0.0);
                    }

                    profiler.push("renderSolidFeatures");
                    featureFrame.executeSolid();
                    profiler.pop();
                    if (translucentTarget != null) {
                        translucentTarget.get().copyDepthFrom(mainTarget.get());
                    }

                    if (itemEntityTarget != null) {
                        itemEntityTarget.get().copyDepthFrom(mainTarget.get());
                    }

                    if (particleTarget != null) {
                        particleTarget.get().copyDepthFrom(mainTarget.get());
                    }

                    profiler.push("renderTranslucentFeatures");
                    featureFrame.executeTranslucent();
                    profiler.pop();
                    featureFrame.executeOutline();
                    profiler.push("translucentTerrain");
                    assert this.chunkLayerSampler != null;
                    chunkSectionsToRender.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, this.chunkLayerSampler);
                    profiler.pop();
                    featureFrame.executeTranslucentAfterTerrain();
                }
        );
    }

    @Unique
    private boolean naturalMotionBlur$shouldUseSpecialSingleBlur() {
        Minecraft client = Minecraft.getInstance();
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) return true;
        return client.player != null && client.player.isPassenger();
    }
}