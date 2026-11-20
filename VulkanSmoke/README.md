# Asterdium 0.0.2 - standalone Vulkan window smoke test

This is the next *research milestone* of Asterdium. It is a small **standalone**
Java 21 + LWJGL 3.4.0 program, not a Fabric mod and not a replacement for
Minecraft's OpenGL renderer.

The first source ZIP had a Vulkan initialization bug: it called `VK.create()`
after LWJGL had already automatically initialized Vulkan. This package fixes
that error in both the standalone window and the optional Fabric GPU probe.

The next test exposed a separate native-stack exhaustion inside LWJGL 3.3.3
when it enumerated Vulkan device extensions. The standalone test now uses
LWJGL 3.4.0 (which fixes that upstream issue) and a 1024 KiB native stack.
The Fabric mod retains its own Minecraft-provided LWJGL dependencies.

**Latest Windows validation fix:** Star ran the official `vkcube` sample
successfully, confirming that the RTX 3060 can present Vulkan frames. Enabling
the Khronos validation layer on Asterdium revealed
`VUID-VkPresentInfoKHR-swapchainCount-arraylength`: the application supplied
`swapchainCount = 0` during presentation, even though it passed a pointer to
one swapchain. This source revision explicitly sets `swapchainCount(1)` and
the submit wait count. LWJGL automatically sets the other array counts when
their corresponding buffers are assigned, so this revision avoids calling
nonexistent setters for those fields. The previous archive revision failed
Java compilation because it attempted those nonexistent setters. This removes
a demonstrated API violation, but **the visible teal frame still requires a
run on Windows to verify**.

The program is designed to:

- Open its own GLFW window with `GLFW_NO_API` (no OpenGL context).
- Create a Vulkan instance with the surface extensions required by GLFW.
- Prefer a Vulkan-capable **discrete GPU** (such as Star's RTX 3060).
- Select a queue family supporting both graphics and surface presentation.
- Create a Vulkan logical device, surface, and swapchain.
- Query the surface's supported presentation modes, using `IMMEDIATE` for the
  one-frame display test when supported (then `MAILBOX`, then `FIFO`). The mode
  is printed in the console; the fixed-size window is still the same.
- Clear a swapchain image **directly via a transfer operation** if supported by
  the Windows surface, or use a render pass and image views as a fallback.
- Submit a single color-clear command buffer and present one frame using semaphores and a fence.
- Destroy Vulkan resources and GLFW resources on exit.

## Run on Windows

1. Keep the whole `Asterdium` folder extracted; do not copy this subdirectory
   into Minecraft's `mods` folder.
2. Install **Java 21 JDK**, as for Asterdium 0.0.1.
3. If needed, run `Build-Asterdium-Windows.cmd` *once* to install the pinned
   Gradle 8.10.2 tools. Existing Asterdium builders should already have them.
4. Double-click **`Run-Vulkan-Smoke-Windows.cmd`** in the parent folder.
5. Gradle may download LWJGL dependencies the first time. A **960x540 window**
   titled `Asterdium 0.0.2 - Vulkan clear` should appear with a solid dark
   teal background. Close it with **Escape** or the X button.

To verify the fix with the installed Vulkan SDK, open Command Prompt inside
the extracted `Asterdium` folder and run:

```cmd
set VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation
Run-Vulkan-Smoke-Windows.cmd
```

If the window stays white, send the `Validation Error` and `VUID` lines (if
any) and the final `[frame 1]` lines. This setting only applies to that CMD
session and is safe to use for this development test.

The console also prints the selected GPU and queue family. If you see an error,
send the **last error lines**. A missing Vulkan driver, blocked GLFW surface,
or missing Gradle dependency is a failed smoke test, not evidence that
Minecraft's existing renderer is broken.

### White or "Not Responding" window

GPU selection and window creation alone do **not** mean that Vulkan has
presented a frame. If the window stays white, take a screenshot showing both
the window and the console's latest `[frame 1]` line. The console logs separate
milestones for GLFW polling, the GPU fence, swapchain image acquisition,
GPU submission, and `vkQueuePresentKHR`; this helps localize the stall.
Fence and image-acquisition waits have two-second timeouts rather than
waiting forever. `vkQueuePresentKHR` itself can still block in the driver.

The follow-up screenshot showed that a single-frame render-pass test kept
the window responsive but **white**, even after `vkQueuePresentKHR` returned
`VK_SUCCESS`. This revision tests a different clear mechanism
(`vkCmdClearColorImage` with explicit image-layout transitions) and checks the
GPU submission fence after presentation is queued. A successful GPU fence
means the recorded work finished, **not** that pixels reached the display.

The direct-clear test then confirmed that the RTX 3060's GPU fence signaled,
but the window was **still white**. This revision tests whether avoiding
`FIFO` presentation changes that result. The console reports supported and
selected presentation modes and the actual swapchain image count.

For a diagnostic comparison without changing the source, you may launch from
the same Command Prompt after running `set ASTERDIUM_PRESENT_MODE=fifo`, then
`Run-Vulkan-Smoke-Windows.cmd`; `mailbox` also works if advertised. This is
only a troubleshooting option and is not required for the default test.

In Star's next trace, **frame 1 submitted and `vkQueuePresentKHR` returned
`VK_SUCCESS`**, but acquiring a second image timed out. This version sends
**only one frame** and then calls `glfwWaitEventsTimeout` while the window is
open. This tests whether the first clear becomes visible without a second
image acquisition starving the Windows message pump. It does not establish
that continuous Vulkan rendering works.

## Limitations and safety

- Star's previous version reached RTX 3060 selection and its first present
  call, but did not demonstrate a visible teal frame. The Khronos layer found
  a concrete zero-swapchain-count error; this revision corrects that error,
  but visible presentation still needs to be retested.
- Fixed-size window only; out-of-date/suboptimal swapchains end the test.
- No shaders, triangles, blocks, texture atlas, depth buffer or chunk upload.
- No Vulkan FPS or Minecraft FPS claims are possible at this stage.
- The existing Fabric `asterdium-0.0.1-dev.jar` is unchanged.
- This experiment does not touch Minecraft worlds, skins, capes, account auth
  or the LuniVex client/launcher.

If this window presents correctly, the next step is a *real graphics pipeline*
and an indexed draw from Asterdium's chunk mesher, still outside Minecraft.
