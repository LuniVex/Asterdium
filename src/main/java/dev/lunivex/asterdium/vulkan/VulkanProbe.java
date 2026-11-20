package dev.lunivex.asterdium.vulkan;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Real Vulkan API instance/device enumeration. It deliberately does not create
 * a device, swapchain or Minecraft render-hook: those need a Vulkan window.
 */
public final class VulkanProbe {
    private VulkanProbe() { }

    public record Device(String name, String apiVersion, int graphicsQueueFamily) { }
    public record Report(String loaderVersion, List<Device> devices) { }

    public static Report inspect() {
        // LWJGL 3.3.3 initializes VK on first use by default. Calling
        // VK.create() explicitly throws "Vulkan has already been created."
        // Also avoid destroying a loader potentially shared with other mods.
        return inspectWithLoader();
    }

    private static Report inspectWithLoader() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int availableVersion = VK.getInstanceVersionSupported();
            VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Asterdium"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("Asterdium"))
                    .engineVersion(1)
                    .apiVersion(VK_API_VERSION_1_0);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(application);
            PointerBuffer instancePointer = stack.mallocPointer(1);
            check(vkCreateInstance(createInfo, null, instancePointer), "vkCreateInstance");
            VkInstance instance = new VkInstance(instancePointer.get(0), createInfo);

            try {
                IntBuffer count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
                if (count.get(0) == 0) return new Report(version(availableVersion), List.of());

                PointerBuffer handles = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices(devices)");
                List<Device> devices = new ArrayList<>();
                for (int i = 0; i < count.get(0); i++) {
                    VkPhysicalDevice physicalDevice = new VkPhysicalDevice(handles.get(i), instance);
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                    vkGetPhysicalDeviceProperties(physicalDevice, properties);

                    IntBuffer queueCount = stack.ints(0);
                    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueCount, null);
                    int graphicsQueue = -1;
                    if (queueCount.get(0) > 0) {
                        VkQueueFamilyProperties.Buffer queues =
                                VkQueueFamilyProperties.calloc(queueCount.get(0), stack);
                        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueCount, queues);
                        for (int q = 0; q < queueCount.get(0); q++) {
                            if ((queues.get(q).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                                graphicsQueue = q;
                                break;
                            }
                        }
                    }
                    devices.add(new Device(properties.deviceNameString(),
                            version(properties.apiVersion()), graphicsQueue));
                }
                return new Report(version(availableVersion), List.copyOf(devices));
            } finally {
                vkDestroyInstance(instance, null);
            }
        }
    }

    private static String version(int packed) {
        return VK_API_VERSION_MAJOR(packed) + "." + VK_API_VERSION_MINOR(packed) + "." + VK_API_VERSION_PATCH(packed);
    }

    private static void check(int status, String operation) {
        if (status != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed (VkResult " + status + ")");
        }
    }
}
