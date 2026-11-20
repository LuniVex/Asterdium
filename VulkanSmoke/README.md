# Asterdium 0.0.3 — Vulkan triangle experiment

This is a **standalone Java 21 + LWJGL 3.4.0 Vulkan program**, not a Fabric
mod, a Minecraft renderer replacement, or a Minecraft benchmark. It does not
modify Minecraft instances, accounts, skins, or capes.

## What it tests

- GLFW `GLFW_NO_API` window (960 × 540) on a Vulkan-capable GPU.
- A real Vulkan instance, device, graphics/present queue and swapchain.
- A render pass, image views, framebuffers and graphics pipeline.
- GLSL vertex/fragment shaders compiled to SPIR-V using shaderc at startup.
- A single `vkCmdDraw(..., 3, 1, 0, 0)` with vertex positions/colors generated
  by `gl_VertexIndex` in the vertex shader (no vertex buffer yet).
- One teal-background frame with a cyan/blue/pink triangle, then responsive
  GLFW event pumping. Escape or the X closes the window.

**Validation status:** the 0.0.2 clear-only code displayed solid teal on
Star's RTX 3060 after the explicit semaphore/swapchain-count fixes. The
0.0.3 triangle path has not yet been visually tested on that GPU. A working
triangle will demonstrate the first graphics-pipeline draw, **not** a working
Minecraft renderer or any FPS improvement.

## Run it on Windows

1. Use Java 21 and the existing Asterdium folder.
2. Run `Build-Asterdium-Windows.cmd` once if the pinned Gradle 8.10.2 tools
   have not yet been installed.
3. Run **`Run-Vulkan-Smoke-Windows.cmd`** in the project's root folder.
4. On the first run, Gradle may download `lwjgl-shaderc` and its Windows native
   library. The window should show the colored triangle.

### Keep the known-good clear as a fallback

The original direct-transfer clear is retained for troubleshooting. In a
Command Prompt opened in the repository root, run:

```cmd
set ASTERDIUM_RENDER_MODE=clear
Run-Vulkan-Smoke-Windows.cmd
```

This changes only that Command Prompt session. Run `set ASTERDIUM_RENDER_MODE=`
to return to the default triangle experiment. The clear-only path still uses
the previously tested direct transfer when the surface supports it.

For presentation diagnostics, `ASTERDIUM_PRESENT_MODE=fifo` (or `mailbox`)
overrides the default preference for `IMMEDIATE`. The program prints the
selected GPU, present mode, swapchain image count, render mode, and first-frame
Vulkan submit/present status to the console.

## Limitations

- One frame is submitted deliberately. This is **not** a continuous render
  loop yet: the earlier test timed out when acquiring a second image.
- Fixed-size window; no swapchain recreation.
- No chunk mesh upload, texture atlas, transparency, depth buffer or Minecraft
  integration.
- The Minecraft 1.21.1 Fabric mod in the parent project remains unchanged.

If the triangle is absent or the program fails, send the first `Validation
Error`/`VUID` line if the Vulkan SDK's validation layer is enabled, along
with the last `[frame 1]` console lines. The previously validated clear mode
will help distinguish shader/pipeline problems from presentation problems.
