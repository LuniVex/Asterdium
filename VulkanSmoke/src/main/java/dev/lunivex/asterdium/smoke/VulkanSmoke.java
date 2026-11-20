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
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Asterdium 0.0.3: an isolated Vulkan graphics-pipeline triangle test.
 * Nothing in this class is invoked by the Fabric Minecraft mod.
 *
 * The default mode draws one colored triangle on the proven teal background.
 * Set ASTERDIUM_RENDER_MODE=clear to keep the original 0.0.2 clear-only test
 * available when debugging drivers or presentation. Neither mode renders
 * Minecraft geometry or measures Minecraft FPS.
 */
public final class VulkanSmoke implements AutoCloseable {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    // An infinite wait hides GPU/WSI failures behind a white "Not Responding" window.
    private static final long FRAME_WAIT_NANOSECONDS = 2_000_000_000L;
    private static final String TRIANGLE_VERTEX_SHADER = """
            #version 450
            layout(location = 0) out vec3 vertexColor;
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
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
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

    private final boolean drawTriangle = !"clear".equalsIgnoreCase(System.getenv("ASTERDIUM_RENDER_MODE"));
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
    private long trianglePipeline;
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
                drawTriangle ? "Asterdium 0.0.3 - Vulkan triangle" : "Asterdium 0.0.3 - Vulkan clear",
                NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Could not create a Vulkan-capable GLFW window");

        createInstanceAndSurface();
        selectGraphicsDevice();
        createLogicalDevice();
        createSwapchain();
        if (!directTransferClear) {
            createRenderPass();
            createImageViewsAndFramebuffers();
            if (drawTriangle) createTrianglePipeline();
        }
        createCommandBuffers();
        createSyncObjects();

        System.out.println("Asterdium Vulkan window ready (" + swapchainWidth + "x" + swapchainHeight + ").");
        System.out.println("Render method: " + (drawTriangle ? "Vulkan graphics pipeline + triangle"
                : directTransferClear ? "direct Vulkan image transfer" : "render-pass clear"));
        System.out.println("Vulkan setup complete; queuing one "
                + (drawTriangle ? "colored triangle" : "dark blue-teal clear") + " frame. Press Escape to close.");
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
            directTransferClear = !drawTriangle
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
            int preferred = "fifo".equalsIgnoreCase(requested) ? VK_PRESENT_MODE_FIFO_KHR
                    : "mailbox".equalsIgnoreCase(requested) ? VK_PRESENT_MODE_MAILBOX_KHR
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

    private void createTrianglePipeline() {
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
            vertexModule = compileShaderModule(compiler, options, TRIANGLE_VERTEX_SHADER,
                    shaderc_vertex_shader, "asterdium_triangle.vert");
            fragmentModule = compileShaderModule(compiler, options, TRIANGLE_FRAGMENT_SHADER,
                    shaderc_fragment_shader, "asterdium_triangle.frag");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
                LongBuffer layoutHandle = stack.mallocLong(1);
                check(vkCreatePipelineLayout(device, layoutInfo, null, layoutHandle), "vkCreatePipelineLayout");
                pipelineLayout = layoutHandle.get(0);

                ByteBuffer entryPoint = stack.UTF8("main");
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(entryPoint);
                stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(entryPoint);

                // gl_VertexIndex supplies all 3 positions, so no vertex buffer is needed yet.
                VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
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
                trianglePipeline = pipelineHandle.get(0);
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

    private void createCommandBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
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
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT);
                check(vkBeginCommandBuffer(commandBuffers[i], begin), "vkBeginCommandBuffer");
                if (directTransferClear) {
                    recordDirectClear(commandBuffers[i], images[i], stack);
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
                            .framebuffer(framebuffers[i])
                            .renderArea(rect)
                            .pClearValues(clear);
                    vkCmdBeginRenderPass(commandBuffers[i], render, VK_SUBPASS_CONTENTS_INLINE);
                    if (drawTriangle) {
                        vkCmdBindPipeline(commandBuffers[i], VK_PIPELINE_BIND_POINT_GRAPHICS, trianglePipeline);
                        vkCmdDraw(commandBuffers[i], 3, 1, 0, 0);
                    }
                    vkCmdEndRenderPass(commandBuffers[i]);
                }
                check(vkEndCommandBuffer(commandBuffers[i]), "vkEndCommandBuffer");
            }
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
        System.out.println("[frame 1] Entering GLFW event loop...");
        // This is a static color smoke test, not a game loop. On Star's first
        // RTX 3060 run, queuing image 0 for presentation succeeded but the *second* image
        // acquisition timed out. Present once, then keep pumping GLFW events
        // so we can tell whether the first submitted image is actually visible.
        glfwPollEvents();
        if (glfwWindowShouldClose(window)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            System.out.println("[frame 1] GLFW responsive; waiting for GPU fence...");
            int fenceResult = vkWaitForFences(device, stack.longs(frameFence), true, FRAME_WAIT_NANOSECONDS);
            if (fenceResult == VK_TIMEOUT) {
                throw new IllegalStateException("GPU frame fence timed out after 2 seconds");
            }
            check(fenceResult, "vkWaitForFences");
            System.out.println("[frame 1] Fence ready; acquiring a swapchain image...");
            IntBuffer acquired = stack.ints(0);
            int result = vkAcquireNextImageKHR(device, swapchain, FRAME_WAIT_NANOSECONDS, imageAvailable,
                    VK_NULL_HANDLE, acquired);
            if (result == VK_TIMEOUT || result == VK_NOT_READY) {
                throw new IllegalStateException("Swapchain image acquisition timed out after 2 seconds");
            }
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                System.out.println("Swapchain out of date; restart the fixed-size smoke test.");
                return;
            }
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
                check(result, "vkAcquireNextImageKHR");
            }
            int imageIndex = acquired.get(0);
            System.out.println("[frame 1] Acquired image " + imageIndex + "; submitting Vulkan "
                    + (drawTriangle ? "triangle" : "clear") + "...");

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
            System.out.println("[frame 1] Submit arrays: wait=" + submit.waitSemaphoreCount()
                    + ", commands=" + submit.commandBufferCount()
                    + ", signal=" + submit.signalSemaphoreCount());
            check(vkQueueSubmit(graphicsQueue, submit, frameFence), "vkQueueSubmit");
            System.out.println("[frame 1] GPU commands submitted; calling vkQueuePresentKHR...");

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderComplete[imageIndex]))
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex))
                    // Validated on Star's RTX 3060: a zero swapchainCount
                    // produced VUID-VkPresentInfoKHR-swapchainCount-arraylength.
                    .swapchainCount(1);
            System.out.println("[frame 1] Present arrays: wait=" + present.waitSemaphoreCount()
                    + ", swapchains=" + present.swapchainCount());
            int presentResult = vkQueuePresentKHR(graphicsQueue, present);
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
                System.out.println("Presentation surface changed; restart the smoke test.");
                return;
            }
            check(presentResult, "vkQueuePresentKHR");
            System.out.println("[frame 1] vkQueuePresentKHR returned VK_SUCCESS. Check the window color.");
            int completed = vkWaitForFences(device, stack.longs(frameFence), true, FRAME_WAIT_NANOSECONDS);
            if (completed == VK_SUCCESS) {
                System.out.println("[gpu] GPU fence signaled: command buffer finished; presentation is separate.");
            } else if (completed == VK_TIMEOUT) {
                System.out.println("[gpu] GPU fence NOT signaled after 2 seconds: queue submission is stalled.");
            } else {
                check(completed, "frame completion fence");
            }
        }

        System.out.println("[display] One Vulkan frame queued; pumping window events without acquiring another image.");
        while (!glfwWindowShouldClose(window)) {
            glfwWaitEventsTimeout(0.05);
            if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
                glfwSetWindowShouldClose(window, true);
            }
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
            if (trianglePipeline != 0) vkDestroyPipeline(device, trianglePipeline, null);
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
