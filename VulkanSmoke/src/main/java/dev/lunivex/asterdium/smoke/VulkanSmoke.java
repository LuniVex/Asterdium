package dev.lunivex.asterdium.smoke;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Asterdium 0.0.5: standalone Vulkan textured-geometry research.
 * Nothing in this class is invoked by the Fabric Minecraft mod.
 *
 * By default, a textured quad rotates on a teal background. Use
 * ASTERDIUM_RENDER_MODE=triangle for the 0.0.4 animated triangle,
 * static for the 0.0.3 one-frame triangle, or clear for the 0.0.2 clear.
 * These are standalone diagnostics, not Minecraft rendering or FPS benchmarks.
 */
public final class VulkanSmoke implements AutoCloseable {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int TEXTURE_SIZE = 32;
    // An infinite wait hides GPU/WSI failures behind a white "Not Responding" window.
    private static final long FRAME_WAIT_NANOSECONDS = 2_000_000_000L;
    private static final String TRIANGLE_VERTEX_SHADER = """
            #version 450
            layout(location = 0) out vec3 vertexColor;
            layout(push_constant) uniform FrameData {
                float elapsedSeconds;
            } frame;
            void main() {
                vec2 positions[3] = vec2[](
                    vec2( 0.00, -0.68),
                    vec2( 0.72,  0.58),
                    vec2(-0.72,  0.58)
                );
                vec3 colors[3] = vec3[](
                    vec3(0.08, 0.95, 0.92),
                    vec3(0.33, 0.54, 1.00),
                    vec3(0.91, 0.36, 0.95)
                );
                float angle = frame.elapsedSeconds * 0.65;
                float c = cos(angle);
                float s = sin(angle);
                vec2 position = positions[gl_VertexIndex];
                vec2 rotated = vec2(position.x * c - position.y * s,
                                    position.x * s + position.y * c);
                gl_Position = vec4(rotated, 0.0, 1.0);
                vertexColor = colors[gl_VertexIndex];
            }
            """;
    private static final String TRIANGLE_FRAGMENT_SHADER = """
            #version 450
            layout(location = 0) in vec3 vertexColor;
            layout(location = 0) out vec4 fragmentColor;
            void main() {
                fragmentColor = vec4(vertexColor, 1.0);
            }
            """;
    private static final String QUAD_VERTEX_SHADER = """
            #version 450
            layout(location = 0) in vec2 inPosition;
            layout(location = 1) in vec2 inUv;
            layout(location = 0) out vec2 fragmentUv;
            layout(push_constant) uniform FrameData { float elapsedSeconds; } frame;
            void main() {
                float angle = frame.elapsedSeconds * 0.65;
                float c = cos(angle), s = sin(angle);
                gl_Position = vec4(inPosition.x * c - inPosition.y * s,
                                   inPosition.x * s + inPosition.y * c, 0.0, 1.0);
                fragmentUv = inUv;
            }
            """;
    private static final String QUAD_FRAGMENT_SHADER = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D albedo;
            layout(location = 0) in vec2 fragmentUv;
            layout(location = 0) out vec4 fragmentColor;
            void main() { fragmentColor = texture(albedo, fragmentUv); }
            """;

    private final String renderMode = System.getenv("ASTERDIUM_RENDER_MODE");
    private final boolean drawTriangle = "triangle".equalsIgnoreCase(renderMode)
            || "static".equalsIgnoreCase(renderMode);
    private final boolean drawTexturedQuad = !drawTriangle && !"clear".equalsIgnoreCase(renderMode);
    private final boolean drawGeometry = drawTriangle || drawTexturedQuad;
    private final boolean animate = drawTexturedQuad || "triangle".equalsIgnoreCase(renderMode);
    private long window;
    private boolean glfwReady;
    private boolean vulkanReady;
    private GLFWErrorCallback errorCallback;

    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private int graphicsFamily = -1;
    private VkDevice device;
    private VkQueue graphicsQueue;

    private long swapchain;
    private int swapchainFormat;
    private int swapchainWidth;
    private int swapchainHeight;
    private boolean directTransferClear;
    private int presentMode;
    private long[] images;
    private long[] imageViews;
    private long[] framebuffers;
    private long renderPass;
    private long pipelineLayout;
    private long graphicsPipeline;
    private long vertexBuffer;
    private long vertexMemory;
    private long indexBuffer;
    private long indexMemory;
    private long textureImage;
    private long textureMemory;
    private long textureView;
    private long textureSampler;
    private long descriptorLayout;
    private long descriptorPool;
    private long descriptorSet;
    private long commandPool;
    private VkCommandBuffer[] commandBuffers;
    private long imageAvailable;
    private long[] renderComplete;
    private long frameFence;

    public static void main(String[] args) {
        try (VulkanSmoke demo = new VulkanSmoke()) {
            demo.initialize();
            demo.runFrames();
        } catch (Throwable failure) {
            System.err.println("Asterdium Vulkan test failed: " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void initialize() {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        glfwReady = true;

        // LWJGL initializes Vulkan in VK's static initializer.
        // An explicit VK.create() here initializes it *twice* and throws.
        VK.getInstanceVersionSupported();
        vulkanReady = true;
        if (!glfwVulkanSupported()) {
            throw new IllegalStateException("GLFW cannot access a Vulkan ICD/driver");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        // A fixed-size smoke test avoids pretending we have swapchain recreation yet.
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        window = glfwCreateWindow(WIDTH, HEIGHT,
                drawTexturedQuad ? "Asterdium 0.0.5 - Textured Vulkan quad"
                        : animate ? "Asterdium 0.0.5 - Animated Vulkan triangle"
                        : drawTriangle ? "Asterdium 0.0.5 - Static Vulkan triangle"
                        : "Asterdium 0.0.5 - Vulkan clear",
                NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Could not create a Vulkan-capable GLFW window");

        createInstanceAndSurface();
        selectGraphicsDevice();
        createLogicalDevice();
        createSwapchain();
        if (!directTransferClear) {
            createRenderPass();
            createImageViewsAndFramebuffers();
            if (drawTexturedQuad) createTextureDescriptorLayout();
            if (drawGeometry) createGraphicsPipeline();
        }
        createCommandPoolAndBuffers();
        if (drawTexturedQuad) createTexturedGeometry();
        createSyncObjects();

        System.out.println("Asterdium Vulkan window ready (" + swapchainWidth + "x" + swapchainHeight + ").");
        System.out.println("Render method: " + (drawTexturedQuad ? "rotating textured Vulkan quad"
                : animate ? "animated Vulkan triangle"
                : drawTriangle ? "static Vulkan triangle"
                : directTransferClear ? "direct Vulkan image transfer" : "render-pass clear"));
        System.out.println("Vulkan setup complete; " + (animate ? "entering the continuous frame loop"
                : "queuing one diagnostic frame") + ". Press Escape to close.");
    }

    private void createInstanceAndSurface() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null || !glfwExtensions.hasRemaining()) {
                throw new IllegalStateException("GLFW supplied no Vulkan surface extensions");
            }
            VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Asterdium Vulkan Smoke"))
                    .applicationVersion(3)
                    .pEngineName(stack.UTF8("Asterdium"))
                    .engineVersion(3)
                    .apiVersion(VK_API_VERSION_1_0);
            VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(app)
                    .ppEnabledExtensionNames(glfwExtensions);
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(create, null, handle), "vkCreateInstance");
            instance = new VkInstance(handle.get(0), create);

            LongBuffer surfaceOut = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, window, null, surfaceOut), "glfwCreateWindowSurface");
            surface = surfaceOut.get(0);
        }
    }

    private void selectGraphicsDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate GPU count");
            if (count.get(0) == 0) throw new IllegalStateException("No Vulkan GPU found");
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, handles), "enumerate GPUs");

            int bestScore = -1;
            String chosenName = "";
            for (int gpuIndex = 0; gpuIndex < count.get(0); gpuIndex++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(gpuIndex), instance);
                int family = findPresentCapableGraphicsFamily(candidate, stack);
                if (family < 0 || !hasSwapchainSupport(candidate, stack)) continue;
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, properties);
                int score = properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2 : 1;
                if (score > bestScore) {
                    bestScore = score;
                    chosenName = properties.deviceNameString();
                    physicalDevice = candidate;
                    graphicsFamily = family;
                }
            }
            if (physicalDevice == null) {
                throw new IllegalStateException("No GPU with graphics + presentation + swapchain support");
            }
            System.out.println("Vulkan GPU: " + chosenName + ", graphics/present queue family " + graphicsFamily);
        }
    }

    private int findPresentCapableGraphicsFamily(VkPhysicalDevice gpu, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, families);
        for (int i = 0; i < count.get(0); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            IntBuffer supported = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceSupportKHR(gpu, i, surface, supported), "surface presentation support");
            if (supported.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    private boolean hasSwapchainSupport(VkPhysicalDevice gpu, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, count, null), "surface format count");
        if (count.get(0) == 0) return false;
        count.put(0, 0);
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, surface, count, null), "present mode count");
        return count.get(0) != 0;
    }

    private void createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsFamily)
                    .pQueuePriorities(stack.floats(1.0f));
            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueInfo)
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
            PointerBuffer deviceHandle = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, createInfo, null, deviceHandle), "vkCreateDevice");
            device = new VkDevice(deviceHandle.get(0), physicalDevice, createInfo);

            PointerBuffer queueHandle = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsFamily, 0, queueHandle);
            graphicsQueue = new VkQueue(queueHandle.get(0), device);
        }
    }

    private void createSwapchain() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities), "surface capabilities");
            if ((capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0) {
                throw new IllegalStateException("Surface does not support color attachment images");
            }
            // Try a second, independent clear path to distinguish render-pass
            // problems from driver/WSI presentation problems on Windows.
            directTransferClear = !drawGeometry
                    && (capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0;

            IntBuffer count = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null), "surface format count");
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats), "surface formats");
            int chosen = 0;
            for (int i = 0; i < count.get(0); i++) {
                if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM
                        && formats.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    chosen = i;
                    break;
                }
            }
            swapchainFormat = formats.get(chosen).format();
            if (swapchainFormat == VK_FORMAT_UNDEFINED) swapchainFormat = VK_FORMAT_B8G8R8A8_UNORM;
            int colorSpace = formats.get(chosen).colorSpace();

            IntBuffer presentModeCount = stack.ints(0);
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, null),
                    "surface present mode count");
            if (presentModeCount.get(0) == 0) throw new IllegalStateException("Surface advertises no present modes");
            IntBuffer presentModes = stack.mallocInt(presentModeCount.get(0));
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, presentModes),
                    "surface present modes");
            StringBuilder available = new StringBuilder();
            for (int i = 0; i < presentModeCount.get(0); i++) {
                if (i != 0) available.append(", ");
                available.append(presentModeName(presentModes.get(i)));
            }
            System.out.println("Supported presentation modes: " + available);
            String requested = System.getenv("ASTERDIUM_PRESENT_MODE");
            // FIFO is guaranteed by Vulkan and avoids excessive frames/tearing.
            // Retain IMMEDIATE for the single-frame modes that Star validated.
            int preferred = "fifo".equalsIgnoreCase(requested) ? VK_PRESENT_MODE_FIFO_KHR
                    : "mailbox".equalsIgnoreCase(requested) ? VK_PRESENT_MODE_MAILBOX_KHR
                    : requested == null && animate ? VK_PRESENT_MODE_FIFO_KHR
                    : VK_PRESENT_MODE_IMMEDIATE_KHR;
            if (supportsPresentMode(presentModes, preferred)) {
                presentMode = preferred;
            } else if (supportsPresentMode(presentModes, VK_PRESENT_MODE_MAILBOX_KHR)) {
                presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
            } else {
                presentMode = VK_PRESENT_MODE_FIFO_KHR;
            }
            System.out.println("Selected presentation mode: " + presentModeName(presentMode));

            if (capabilities.currentExtent().width() != -1) {
                swapchainWidth = capabilities.currentExtent().width();
                swapchainHeight = capabilities.currentExtent().height();
            } else {
                IntBuffer framebufferWidth = stack.ints(0);
                IntBuffer framebufferHeight = stack.ints(0);
                glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
                swapchainWidth = clamp(framebufferWidth.get(0), capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                swapchainHeight = clamp(framebufferHeight.get(0), capabilities.minImageExtent().height(), capabilities.maxImageExtent().height());
            }
            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) imageCount = Math.min(imageCount, capabilities.maxImageCount());

            int alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            if ((capabilities.supportedCompositeAlpha() & alpha) == 0) {
                alpha = Integer.lowestOneBit(capabilities.supportedCompositeAlpha());
            }
            if (alpha == 0) throw new IllegalStateException("Surface has no composite alpha modes");

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(swapchainFormat)
                    .imageColorSpace(colorSpace)
                    .imageExtent(VkExtent2D.calloc(stack).set(swapchainWidth, swapchainHeight))
                    .imageArrayLayers(1)
                    .imageUsage(directTransferClear ? VK_IMAGE_USAGE_TRANSFER_DST_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(alpha)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(VK_NULL_HANDLE);
            LongBuffer swapchainOut = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, createInfo, null, swapchainOut), "vkCreateSwapchainKHR");
            swapchain = swapchainOut.get(0);

            count.put(0, 0);
            check(vkGetSwapchainImagesKHR(device, swapchain, count, null), "swapchain image count");
            LongBuffer imageHandles = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(device, swapchain, count, imageHandles), "swapchain images");
            images = new long[count.get(0)];
            for (int i = 0; i < images.length; i++) images[i] = imageHandles.get(i);
            System.out.println("Swapchain images: " + images.length + ", surface minimum: "
                    + capabilities.minImageCount() + ", format: " + swapchainFormat);
        }
    }

    private void createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
            attachment.get(0)
                    .format(swapchainFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            VkAttachmentReference.Buffer color = VkAttachmentReference.calloc(1, stack);
            color.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpasses = VkSubpassDescription.calloc(1, stack);
            subpasses.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    // LWJGL does not derive this count from pColorAttachments.
                    // Without it, the subpass writes no color attachment at all.
                    .colorAttachmentCount(1)
                    .pColorAttachments(color);
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
            dependency.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            VkRenderPassCreateInfo createInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachment)
                    .pSubpasses(subpasses)
                    .pDependencies(dependency);
            LongBuffer result = stack.mallocLong(1);
            check(vkCreateRenderPass(device, createInfo, null, result), "vkCreateRenderPass");
            renderPass = result.get(0);
        }
    }

    private void createImageViewsAndFramebuffers() {
        imageViews = new long[images.length];
        framebuffers = new long[images.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            for (int i = 0; i < images.length; i++) {
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(images[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(swapchainFormat)
                        .subresourceRange(range);
                LongBuffer viewOut = stack.mallocLong(1);
                check(vkCreateImageView(device, view, null, viewOut), "vkCreateImageView");
                imageViews[i] = viewOut.get(0);

                VkFramebufferCreateInfo framebuffer = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(renderPass)
                        .pAttachments(stack.longs(imageViews[i]))
                        .width(swapchainWidth)
                        .height(swapchainHeight)
                        .layers(1);
                LongBuffer framebufferOut = stack.mallocLong(1);
                check(vkCreateFramebuffer(device, framebuffer, null, framebufferOut), "vkCreateFramebuffer");
                framebuffers[i] = framebufferOut.get(0);
            }
        }
    }

    private void createTextureDescriptorLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);
            LongBuffer result = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, info, null, result), "vkCreateDescriptorSetLayout");
            descriptorLayout = result.get(0);
        }
    }

    private void createGraphicsPipeline() {
        long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) throw new IllegalStateException("shaderc could not initialize");
        long options = shaderc_compile_options_initialize();
        if (options == NULL) {
            shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc options could not initialize");
        }

        long vertexModule = VK_NULL_HANDLE;
        long fragmentModule = VK_NULL_HANDLE;
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_0);
            vertexModule = compileShaderModule(compiler, options,
                    drawTexturedQuad ? QUAD_VERTEX_SHADER : TRIANGLE_VERTEX_SHADER,
                    shaderc_vertex_shader, drawTexturedQuad ? "asterdium_quad.vert" : "asterdium_triangle.vert");
            fragmentModule = compileShaderModule(compiler, options,
                    drawTexturedQuad ? QUAD_FRAGMENT_SHADER : TRIANGLE_FRAGMENT_SHADER,
                    shaderc_fragment_shader, drawTexturedQuad ? "asterdium_quad.frag" : "asterdium_triangle.frag");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack);
                pushConstants.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                        .offset(0).size(Float.BYTES);
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                        .pPushConstantRanges(pushConstants);
                if (drawTexturedQuad) layoutInfo.pSetLayouts(stack.longs(descriptorLayout));
                LongBuffer layoutHandle = stack.mallocLong(1);
                check(vkCreatePipelineLayout(device, layoutInfo, null, layoutHandle), "vkCreatePipelineLayout");
                pipelineLayout = layoutHandle.get(0);

                ByteBuffer entryPoint = stack.UTF8("main");
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(entryPoint);
                stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(entryPoint);

                // The legacy triangle uses gl_VertexIndex; the quad takes position/UV
                // from an interleaved, explicitly bound vertex buffer.
                VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
                if (drawTexturedQuad) {
                    VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(1, stack);
                    bindings.get(0).binding(0).stride(4 * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                    VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(2, stack);
                    attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
                    attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(2 * Float.BYTES);
                    vertexInput.pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes)
                            .vertexBindingDescriptionCount(1).vertexAttributeDescriptionCount(2);
                }
                VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

                VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
                viewport.get(0).x(0).y(0).width(swapchainWidth).height(swapchainHeight)
                        .minDepth(0).maxDepth(1);
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.get(0).offset().set(0, 0);
                scissor.get(0).extent().set(swapchainWidth, swapchainHeight);
                VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        .viewportCount(1)
                        .scissorCount(1)
                        .pViewports(viewport).pScissors(scissor);

                VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .cullMode(VK_CULL_MODE_NONE)
                        .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                        .lineWidth(1.0f);
                VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
                blendAttachment.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
                VkPipelineColorBlendStateCreateInfo blending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .attachmentCount(1)
                        .pAttachments(blendAttachment);

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(assembly)
                        .pViewportState(viewportState)
                        .pRasterizationState(rasterizer)
                        .pMultisampleState(multisampling)
                        .pColorBlendState(blending)
                        .layout(pipelineLayout)
                        .renderPass(renderPass)
                        .subpass(0);
                LongBuffer pipelineHandle = stack.mallocLong(1);
                check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pipelineHandle),
                        "vkCreateGraphicsPipelines");
                graphicsPipeline = pipelineHandle.get(0);
            }
            System.out.println("Compiled vertex + fragment shaders and created a Vulkan graphics pipeline.");
        } finally {
            if (fragmentModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, fragmentModule, null);
            if (vertexModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, vertexModule, null);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private long compileShaderModule(long compiler, long options, String source, int kind, String filename) {
        long compiled = shaderc_compile_into_spv(compiler, source, kind, filename, "main", options);
        if (compiled == NULL) throw new IllegalStateException("shaderc failed to compile " + filename);
        try {
            if (shaderc_result_get_compilation_status(compiled) != shaderc_compilation_status_success) {
                throw new IllegalStateException(filename + ": " + shaderc_result_get_error_message(compiled));
            }
            ByteBuffer spirv = shaderc_result_get_bytes(compiled);
            if (spirv == null || spirv.remaining() == 0) {
                throw new IllegalStateException("shaderc produced empty SPIR-V for " + filename);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(spirv);
                LongBuffer moduleHandle = stack.mallocLong(1);
                check(vkCreateShaderModule(device, createInfo, null, moduleHandle), "vkCreateShaderModule " + filename);
                return moduleHandle.get(0);
            }
        } finally {
            shaderc_result_release(compiled);
        }
    }

    private void createCommandPoolAndBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsFamily);
            LongBuffer poolOut = stack.mallocLong(1);
            check(vkCreateCommandPool(device, poolInfo, null, poolOut), "vkCreateCommandPool");
            commandPool = poolOut.get(0);

            VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(images.length);
            PointerBuffer commandPointers = stack.mallocPointer(images.length);
            check(vkAllocateCommandBuffers(device, alloc, commandPointers), "vkAllocateCommandBuffers");
            commandBuffers = new VkCommandBuffer[images.length];
            for (int i = 0; i < images.length; i++) {
                commandBuffers[i] = new VkCommandBuffer(commandPointers.get(i), device);
            }
        }
    }

    private record BufferAllocation(long buffer, long memory) { }

    private int findMemoryType(int allowedTypes, int requiredProperties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                if ((allowedTypes & (1 << i)) != 0
                        && (properties.memoryTypes(i).propertyFlags() & requiredProperties) == requiredProperties) {
                    return i;
                }
            }
            throw new IllegalStateException("No suitable Vulkan memory type for flags " + requiredProperties);
        }
    }

    private BufferAllocation createHostBuffer(ByteBuffer data, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(data.remaining())
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer bufferOut = stack.mallocLong(1);
            check(vkCreateBuffer(device, bufferInfo, null, bufferOut), "vkCreateBuffer");
            long buffer = bufferOut.get(0);
            long memory = VK_NULL_HANDLE;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, requirements);
                VkMemoryAllocateInfo allocate = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(requirements.size())
                        .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(),
                                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
                LongBuffer memoryOut = stack.mallocLong(1);
                check(vkAllocateMemory(device, allocate, null, memoryOut), "vkAllocateMemory for buffer");
                memory = memoryOut.get(0);
                check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
                PointerBuffer mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, memory, 0, data.remaining(), 0, mapped), "vkMapMemory");
                try {
                    memCopy(memAddress(data), mapped.get(0), data.remaining());
                } finally {
                    vkUnmapMemory(device, memory);
                }
                return new BufferAllocation(buffer, memory);
            } catch (Throwable failure) {
                vkDestroyBuffer(device, buffer, null);
                if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, null);
                throw failure;
            }
        }
    }

    private void createTexturedGeometry() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Four interleaved XY/UV vertices; two indexed triangles share corners.
            ByteBuffer vertices = stack.malloc(4 * 4 * Float.BYTES);
            vertices.asFloatBuffer().put(new float[] {
                    -0.62f, -0.78f, 0.0f, 1.0f,
                     0.62f, -0.78f, 1.0f, 1.0f,
                     0.62f,  0.78f, 1.0f, 0.0f,
                    -0.62f,  0.78f, 0.0f, 0.0f
            });
            BufferAllocation vertex = createHostBuffer(vertices, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            vertexBuffer = vertex.buffer();
            vertexMemory = vertex.memory();

            ByteBuffer indices = stack.malloc(6 * Integer.BYTES);
            indices.asIntBuffer().put(new int[] {0, 1, 2, 2, 3, 0});
            BufferAllocation index = createHostBuffer(indices, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            indexBuffer = index.buffer();
            indexMemory = index.memory();

            // Hand-made 32x32 pixel texture, no files/network/borrowed art.
            // Sampling this in the fragment shader tests real image upload and UVs.
            ByteBuffer pixels = stack.malloc(TEXTURE_SIZE * TEXTURE_SIZE * 4);
            int[][] palette = {
                    {30, 220, 226}, {75, 125, 255}, {210, 82, 236}
            };
            for (int y = 0; y < TEXTURE_SIZE; y++) {
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    int[] rgb = palette[((x / 8) + (y / 8)) % palette.length];
                    boolean seam = x % 8 == 0 || y % 8 == 0;
                    pixels.put((byte) (seam ? 12 : rgb[0]));
                    pixels.put((byte) (seam ? 32 : rgb[1]));
                    pixels.put((byte) (seam ? 48 : rgb[2]));
                    pixels.put((byte) 255);
                }
            }
            pixels.flip();
            BufferAllocation staging = createHostBuffer(pixels, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            try {
                createTextureImage();
                uploadTexture(staging.buffer());
            } finally {
                vkDestroyBuffer(device, staging.buffer(), null);
                vkFreeMemory(device, staging.memory(), null);
            }
            createTextureViewAndSampler();
            createTextureDescriptor();
            System.out.println("Uploaded 4 vertices, 6 indices and a 32x32 LuniVex texture to Vulkan.");
        }
    }

    private void createTextureImage() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .extent(VkExtent3D.calloc(stack).set(TEXTURE_SIZE, TEXTURE_SIZE, 1))
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer imageOut = stack.mallocLong(1);
            check(vkCreateImage(device, info, null, imageOut), "vkCreateImage(texture)");
            textureImage = imageOut.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, textureImage, requirements);
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer memoryOut = stack.mallocLong(1);
            check(vkAllocateMemory(device, allocation, null, memoryOut), "vkAllocateMemory(texture)");
            textureMemory = memoryOut.get(0);
            check(vkBindImageMemory(device, textureImage, textureMemory, 0), "vkBindImageMemory(texture)");
        }
    }

    private void uploadTexture(long stagingBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, allocate, handle), "allocate texture upload commands");
            VkCommandBuffer commands = new VkCommandBuffer(handle.get(0), device);
            try {
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(commands, begin), "begin texture upload");
                VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1);
                VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(1, stack);
                VkImageMemoryBarrier barrier = barriers.get(0)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(textureImage)
                        .subresourceRange(range)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barriers);

                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                copy.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                copy.get(0).imageOffset().set(0, 0, 0);
                copy.get(0).imageExtent().set(TEXTURE_SIZE, TEXTURE_SIZE, 1);
                vkCmdCopyBufferToImage(commands, stagingBuffer, textureImage,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);

                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barriers);
                check(vkEndCommandBuffer(commands), "end texture upload");

                VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(commands.address()));
                check(vkQueueSubmit(graphicsQueue, submit, VK_NULL_HANDLE), "submit texture upload");
                // Upload only: the continuous frame loop never waits idle per frame.
                check(vkQueueWaitIdle(graphicsQueue), "wait for texture upload");
            } finally {
                vkFreeCommandBuffers(device, commandPool, stack.pointers(commands.address()));
            }
        }
    }

    private void createTextureViewAndSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(textureImage).viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM).subresourceRange(range);
            LongBuffer viewOut = stack.mallocLong(1);
            check(vkCreateImageView(device, view, null, viewOut), "vkCreateImageView(texture)");
            textureView = viewOut.get(0);

            VkSamplerCreateInfo sampler = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f).minLod(0.0f)
                    .anisotropyEnable(false).unnormalizedCoordinates(false);
            LongBuffer samplerOut = stack.mallocLong(1);
            check(vkCreateSampler(device, sampler, null, samplerOut), "vkCreateSampler");
            textureSampler = samplerOut.get(0);
        }
    }

    private void createTextureDescriptor() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1).pPoolSizes(poolSizes);
            LongBuffer poolOut = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, poolInfo, null, poolOut), "vkCreateDescriptorPool");
            descriptorPool = poolOut.get(0);

            VkDescriptorSetAllocateInfo allocation = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorLayout));
            LongBuffer setOut = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(device, allocation, setOut), "vkAllocateDescriptorSets");
            descriptorSet = setOut.get(0);

            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
            image.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(textureView).sampler(textureSampler);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(0).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(image);
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private void recordFrameCommands(int imageIndex, float elapsedSeconds) {
        VkCommandBuffer commands = commandBuffers[imageIndex];
        // Only one GPU submission is in flight. The previous frame's fence is
        // waited before this reset, so the command buffer is no longer pending.
        check(vkResetCommandBuffer(commands, 0), "vkResetCommandBuffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commands, begin), "vkBeginCommandBuffer");

            if (directTransferClear) {
                recordDirectClear(commands, images[imageIndex], stack);
            } else {
                VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
                clear.get(0).color().float32(0, 0.015f).float32(1, 0.12f)
                        .float32(2, 0.16f).float32(3, 1.0f);
                VkRect2D rect = VkRect2D.calloc(stack);
                rect.offset().set(0, 0);
                rect.extent().set(swapchainWidth, swapchainHeight);
                VkRenderPassBeginInfo render = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(renderPass)
                        .framebuffer(framebuffers[imageIndex])
                        .renderArea(rect)
                        .pClearValues(clear);
                vkCmdBeginRenderPass(commands, render, VK_SUBPASS_CONTENTS_INLINE);
                if (drawGeometry) {
                    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
                    vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
                            0, stack.floats(elapsedSeconds));
                    if (drawTexturedQuad) {
                        vkCmdBindVertexBuffers(commands, 0, stack.longs(vertexBuffer), stack.longs(0));
                        vkCmdBindIndexBuffer(commands, indexBuffer, 0, VK_INDEX_TYPE_UINT32);
                        vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                pipelineLayout, 0, stack.longs(descriptorSet), null);
                        vkCmdDrawIndexed(commands, 6, 1, 0, 0, 0);
                    } else {
                        vkCmdDraw(commands, 3, 1, 0, 0);
                    }
                }
                vkCmdEndRenderPass(commands);
            }
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
        }
    }

    private static void recordDirectClear(VkCommandBuffer commandBuffer, long image, MemoryStack stack) {
        VkImageSubresourceRange.Buffer ranges = VkImageSubresourceRange.calloc(1, stack);
        ranges.get(0)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(1, stack);
        VkImageMemoryBarrier barrier = barriers.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(ranges.get(0));
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barriers);

        VkClearColorValue clearColor = VkClearColorValue.calloc(stack)
                .float32(0, 0.015f).float32(1, 0.12f)
                .float32(2, 0.16f).float32(3, 1.0f);
        vkCmdClearColorImage(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, ranges);

        barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(0)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barriers);
    }

    private void createSyncObjects() {
        renderComplete = new long[images.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer semaphore = stack.mallocLong(1);
            check(vkCreateSemaphore(device, semaphoreInfo, null, semaphore), "image-available semaphore");
            imageAvailable = semaphore.get(0);
            // Presentation semaphores belong to swapchain images, not CPU frames.
            for (int i = 0; i < renderComplete.length; i++) {
                check(vkCreateSemaphore(device, semaphoreInfo, null, semaphore), "render-complete semaphore");
                renderComplete[i] = semaphore.get(0);
            }

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer fence = stack.mallocLong(1);
            check(vkCreateFence(device, fenceInfo, null, fence), "frame fence");
            frameFence = fence.get(0);
        }
    }

    private void runFrames() {
        System.out.println("[loop] " + (animate ? "Continuous animation" : "Single-frame diagnostic")
                + " started. Press Escape to exit.");
        long startNanos = System.nanoTime();
        long reportNanos = startNanos;
        int framesSinceReport = 0;
        long frameNumber = 0;
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) break;

            float elapsedSeconds = animate
                    ? (System.nanoTime() - startNanos) / 1_000_000_000.0f : 0.0f;
            if (!drawFrame(++frameNumber, elapsedSeconds)) break;

            if (!animate) {
                // Both legacy diagnostics intentionally show just one image.
                // The active animation path is the separate multi-frame test.
                System.out.println("[display] One diagnostic frame queued; waiting for window events.");
                while (!glfwWindowShouldClose(window)) {
                    glfwWaitEventsTimeout(0.05);
                    if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) break;
                }
                break;
            }

            framesSinceReport++;
            long now = System.nanoTime();
            if (now - reportNanos >= 1_000_000_000L) {
                double interval = (now - reportNanos) / 1_000_000_000.0;
                System.out.printf("[loop] %.1f frames queued/sec (%d total)%n",
                        framesSinceReport / interval, frameNumber);
                reportNanos = now;
                framesSinceReport = 0;
            }
        }
        System.out.println("[loop] Exiting after " + frameNumber + " submitted frame(s).");
    }

    private boolean drawFrame(long frameNumber, float elapsedSeconds) {
        boolean firstFrame = frameNumber == 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (firstFrame) System.out.println("[frame 1] Waiting for GPU fence...");
            int fenceResult = vkWaitForFences(device, stack.longs(frameFence), true, FRAME_WAIT_NANOSECONDS);
            if (fenceResult == VK_TIMEOUT) {
                throw new IllegalStateException("GPU frame fence timed out after 2 seconds");
            }
            check(fenceResult, "vkWaitForFences");
            if (firstFrame) System.out.println("[frame 1] Fence ready; acquiring a swapchain image...");
            IntBuffer acquired = stack.ints(0);
            int result = vkAcquireNextImageKHR(device, swapchain, FRAME_WAIT_NANOSECONDS, imageAvailable,
                    VK_NULL_HANDLE, acquired);
            if (result == VK_TIMEOUT || result == VK_NOT_READY) {
                throw new IllegalStateException("Swapchain image acquisition stalled at frame " + frameNumber
                        + " after 2 seconds; try ASTERDIUM_PRESENT_MODE=fifo");
            }
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                System.out.println("Swapchain out of date; restart the fixed-size smoke test.");
                return false;
            }
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
                check(result, "vkAcquireNextImageKHR");
            }
            int imageIndex = acquired.get(0);
            if (firstFrame) System.out.println("[frame 1] Acquired image " + imageIndex
                    + "; recording and submitting Vulkan "
                    + (drawTexturedQuad ? "textured quad" : drawTriangle ? "triangle" : "clear") + "...");

            recordFrameCommands(imageIndex, elapsedSeconds);

            check(vkResetFences(device, stack.longs(frameFence)), "vkResetFences");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pWaitSemaphores(stack.longs(imageAvailable))
                    .pWaitDstStageMask(stack.ints(directTransferClear
                            ? VK_PIPELINE_STAGE_TRANSFER_BIT
                            : VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(commandBuffers[imageIndex].address()))
                    .pSignalSemaphores(stack.longs(renderComplete[imageIndex]))
                    // LWJGL derives commandBufferCount and signalSemaphoreCount
                    // from their buffers. The shared wait count is explicit.
                    .waitSemaphoreCount(1);
            if (firstFrame) System.out.println("[frame 1] Submit arrays: wait=" + submit.waitSemaphoreCount()
                    + ", commands=" + submit.commandBufferCount()
                    + ", signal=" + submit.signalSemaphoreCount());
            check(vkQueueSubmit(graphicsQueue, submit, frameFence), "vkQueueSubmit");
            if (firstFrame) System.out.println("[frame 1] Submitted; calling vkQueuePresentKHR...");

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderComplete[imageIndex]))
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex))
                    // Validated on Star's RTX 3060: a zero swapchainCount
                    // produced VUID-VkPresentInfoKHR-swapchainCount-arraylength.
                    .swapchainCount(1);
            if (firstFrame) System.out.println("[frame 1] Present arrays: wait=" + present.waitSemaphoreCount()
                    + ", swapchains=" + present.swapchainCount());
            int presentResult = vkQueuePresentKHR(graphicsQueue, present);
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
                System.out.println("Presentation surface changed; restart the smoke test.");
                return false;
            }
            check(presentResult, "vkQueuePresentKHR");
            if (firstFrame) System.out.println("[frame 1] vkQueuePresentKHR returned VK_SUCCESS.");
            // This CPU/GPU fence only covers command execution, not the
            // presentation engine; the per-swapchain-image semaphore protects
            // reuse when the same image is later acquired again.
            return true;
        }
    }

    @Override
    public void close() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (frameFence != 0) vkDestroyFence(device, frameFence, null);
            if (imageAvailable != 0) vkDestroySemaphore(device, imageAvailable, null);
            if (renderComplete != null) {
                for (long semaphore : renderComplete) {
                    if (semaphore != 0) vkDestroySemaphore(device, semaphore, null);
                }
            }
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            if (framebuffers != null) {
                for (long framebuffer : framebuffers) {
                    if (framebuffer != 0) vkDestroyFramebuffer(device, framebuffer, null);
                }
            }
            if (imageViews != null) {
                for (long imageView : imageViews) {
                    if (imageView != 0) vkDestroyImageView(device, imageView, null);
                }
            }
            if (graphicsPipeline != 0) vkDestroyPipeline(device, graphicsPipeline, null);
            if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
            if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
            if (textureSampler != 0) vkDestroySampler(device, textureSampler, null);
            if (textureView != 0) vkDestroyImageView(device, textureView, null);
            if (textureImage != 0) vkDestroyImage(device, textureImage, null);
            if (textureMemory != 0) vkFreeMemory(device, textureMemory, null);
            if (indexBuffer != 0) vkDestroyBuffer(device, indexBuffer, null);
            if (indexMemory != 0) vkFreeMemory(device, indexMemory, null);
            if (vertexBuffer != 0) vkDestroyBuffer(device, vertexBuffer, null);
            if (vertexMemory != 0) vkFreeMemory(device, vertexMemory, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (renderPass != 0) vkDestroyRenderPass(device, renderPass, null);
            if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
            vkDestroyDevice(device, null);
        }
        if (instance != null) {
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            vkDestroyInstance(instance, null);
        }
        if (window != 0) glfwDestroyWindow(window);
        if (glfwReady) glfwTerminate();
        if (vulkanReady) VK.destroy();
        if (errorCallback != null) {
            glfwSetErrorCallback(null);
            errorCallback.free();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean supportsPresentMode(IntBuffer modes, int mode) {
        for (int i = 0; i < modes.limit(); i++) {
            if (modes.get(i) == mode) return true;
        }
        return false;
    }

    private static String presentModeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "mode " + mode;
        };
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " returned VkResult " + result);
    }
}
