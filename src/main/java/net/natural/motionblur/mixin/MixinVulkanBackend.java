package net.natural.motionblur.mixin;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanBackend")
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
            method = "createDevice*",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"),
            remap = false
    )
    private void naturalmotionblur$addExternalSharingExtensionsToVkDevice(Args args) {
        Collection<String> extensions = args.get(0);
        VulkanPhysicalDevice physicalDevice = args.get(1);
        args.set(0, naturalmotionblur$addExternalSharingExtensions(extensions, physicalDevice));
    }

    @ModifyArgs(
            method = "createDevice*",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanDevice;<init>(Lcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/vulkan/VulkanInstance;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;Lorg/lwjgl/vulkan/VkDevice;JLcom/mojang/blaze3d/vulkan/checkpoints/CheckpointExtension;)V"),
            remap = false
    )
    private void naturalmotionblur$addExternalSharingExtensionsToDeviceInfo(Args args) {
        VulkanPhysicalDevice physicalDevice = args.get(2);
        Set<String> extensions = args.get(3);
        args.set(3, naturalmotionblur$addExternalSharingExtensions(extensions, physicalDevice));
    }

    @Unique private static Set<String> naturalmotionblur$addExternalSharingExtensions(Collection<String> extensions, VulkanPhysicalDevice physicalDevice) {
        Set<String> requested = new HashSet<>(extensions);
        if (!naturalmotionblur$externalSharingEnabled()) {
            if (!loggedDisabled) {
                loggedDisabled = true;
                System.out.println("[NMB] Vulkan Spout external sharing disabled.");
            }
            return requested;
        }

        for (String extension : REQUIRED_EXTERNAL_SHARING_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(extension)) {
                if (!loggedUnsupported) {
                    loggedUnsupported = true;
                    System.out.println("[NMB] Vulkan Spout external sharing unavailable; missing " + extension + ".");
                }
                return requested;
            }
        }

        Collections.addAll(requested, REQUIRED_EXTERNAL_SHARING_EXTENSIONS);

        for (String extension : OPTIONAL_EXTERNAL_SHARING_EXTENSIONS) {
            if (physicalDevice.hasDeviceExtension(extension)) {
                requested.add(extension);
            }
        }

        if (!loggedRequest) {
            loggedRequest = true;
            System.out.println("[NMB] Vulkan Spout external sharing enabled.");
        }
        return requested;
    }

    @Unique
    private static boolean naturalmotionblur$externalSharingEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(EXTERNAL_SHARING_PROPERTY));
    }
}