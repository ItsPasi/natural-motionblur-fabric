package net.natural.motionblur.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanBackend", remap = false)
public abstract class MixinVulkanBackend {
    @Unique private static final String EXTERNAL_SHARING_PROPERTY = "naturalmotionblur.vulkanSpoutExternal";

    @Unique private static final String[] REQUIRED_EXTERNAL_SHARING_EXTENSIONS = new String[]{
            "VK_KHR_external_memory",
            "VK_KHR_external_memory_win32",
            "VK_KHR_external_semaphore",
            "VK_KHR_external_semaphore_win32"
    };

    @Unique private static final String[] OPTIONAL_EXTERNAL_SHARING_EXTENSIONS = new String[]{
            "VK_KHR_external_fence",
            "VK_KHR_external_fence_win32",
            "VK_KHR_win32_keyed_mutex"
    };

    @Unique private static boolean loggedRequest = false;
    @Unique private static boolean loggedUnsupported = false;
    @Unique private static boolean loggedDisabled = false;

    @ModifyArgs(
            method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"
            ),
            remap = false
    )
    private void naturalMotionBlur$addExternalSharingExtensionsToVkDevice(Args args) {
        FeatureSet enabledFeatures = args.get(0);
        VulkanPhysicalDevice physicalDevice = args.get(1);
        args.set(0, naturalMotionBlur$addExternalSharingExtensions(enabledFeatures, physicalDevice));
    }

    @ModifyArgs(
            method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;<init>(Lcom/mojang/renderpearl/backend/vulkan/VulkanInstance;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lorg/lwjgl/vulkan/VkDevice;JLcom/mojang/renderpearl/backend/vulkan/checkpoints/CheckpointExtension;)V"
            ),
            remap = false
    )
    private void naturalMotionBlur$addExternalSharingExtensionsToDeviceInfo(Args args) {
        VulkanPhysicalDevice physicalDevice = args.get(1);
        FeatureSet enabledFeatures = args.get(2);
        args.set(2, naturalMotionBlur$addExternalSharingExtensions(enabledFeatures, physicalDevice));
    }

    @Unique
    private static FeatureSet naturalMotionBlur$addExternalSharingExtensions(
            FeatureSet enabledFeatures,
            VulkanPhysicalDevice physicalDevice) {
        if (!naturalMotionBlur$externalSharingEnabled()) {
            if (!loggedDisabled) {
                loggedDisabled = true;
                System.out.println("[NMB] Vulkan Spout external sharing disabled.");
            }
            return enabledFeatures;
        }

        for (String extension : REQUIRED_EXTERNAL_SHARING_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(extension)) {
                if (!loggedUnsupported) {
                    loggedUnsupported = true;
                    System.out.println("[NMB] Vulkan Spout external sharing unavailable; missing " + extension + ".");
                }
                return enabledFeatures;
            }
        }

        Set<String> extensions = new HashSet<>();
        Collections.addAll(extensions, REQUIRED_EXTERNAL_SHARING_EXTENSIONS);
        for (String extension : OPTIONAL_EXTERNAL_SHARING_EXTENSIONS) {
            if (physicalDevice.hasDeviceExtension(extension)) extensions.add(extension);
        }

        if (!loggedRequest) {
            loggedRequest = true;
            System.out.println("[NMB] Vulkan Spout external sharing enabled.");
        }

        return enabledFeatures.composite(new FeatureSet(
                "NaturalMotionBlur Spout external sharing",
                extensions,
                Set.of()));
    }

    @Unique
    private static boolean naturalMotionBlur$externalSharingEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(EXTERNAL_SHARING_PROPERTY));
    }
}