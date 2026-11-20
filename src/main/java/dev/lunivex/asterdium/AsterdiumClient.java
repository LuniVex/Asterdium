package dev.lunivex.asterdium;

import dev.lunivex.asterdium.vulkan.VulkanProbe;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asterdium is deliberately non-invasive in this first milestone.
 * Never hook the Minecraft render loop before a separate Vulkan swapchain works.
 */
public final class AsterdiumClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("Asterdium");

    @Override
    public void onInitializeClient() {
        LOG.info("Asterdium {} loaded; Minecraft remains on its OpenGL renderer.", "0.0.1-dev");

        if (!Boolean.getBoolean("asterdium.probe")) {
            LOG.info("Set -Dasterdium.probe=true to enumerate Vulkan-capable GPUs (diagnostics only).");
            return;
        }

        try {
            VulkanProbe.Report report = VulkanProbe.inspect();
            LOG.info("Vulkan loader API: {}", report.loaderVersion());
            for (VulkanProbe.Device device : report.devices()) {
                LOG.info("Vulkan GPU: {}, API {}, graphics queue family {}",
                        device.name(), device.apiVersion(), device.graphicsQueueFamily());
            }
        } catch (RuntimeException | LinkageError error) {
            // A missing/incompatible driver must not prevent Minecraft from launching.
            LOG.warn("Vulkan diagnostics unavailable; OpenGL is unaffected: {}", error.toString());
        }
    }
}
