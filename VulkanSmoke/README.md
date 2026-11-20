# Asterdium 0.0.5 — Textured Vulkan quad experiment

This is a **standalone Java 21 + LWJGL 3.4.0 Vulkan program**, not a Fabric
mod, a Minecraft renderer replacement, or a Minecraft benchmark. It does not
modify Minecraft instances, accounts, skins, or capes.

## What it tests

- GLFW `GLFW_NO_API` window (960 × 540) on a Vulkan-capable GPU.
- A real Vulkan instance, device, graphics/present queue and swapchain.
- A render pass, image views, framebuffers and graphics pipeline.
- GLSL vertex/fragment shaders compiled to SPIR-V using shaderc at startup.
- Four UV-mapped vertices and six indices stored in real Vulkan buffers,
  rendered with `vkCmdBindVertexBuffers`, `vkCmdBindIndexBuffer`, and
  `vkCmdDrawIndexed`.
- A small original 32×32 cyan/blue/magenta pixel texture, uploaded via a
  staging buffer into a device-local Vulkan image. A descriptor set and
  nearest-neighbor sampler make it visible in the fragment shader.
- A small Vulkan push constant for rotation time, updated when recording each
  frame. A single in-flight GPU fence, command-buffer resets, and presentation
  semaphores indexed by the acquired swapchain image.
- A continuously rotating, textured LuniVex-colored quad on a dark teal background.
  Escape or the X closes the window; the terminal prints frame throughput
  approximately once per second.

**Validation status:** the 0.0.2 clear, 0.0.3 graphics-pipeline triangle, and
0.0.4 rotating triangle were displayed successfully on Star's RTX 3060.
**0.0.5's textured quad is awaiting a Windows GPU test.** A successful test
would demonstrate vertex/index data and texture sampling, **not** a working
Minecraft renderer or any Minecraft FPS improvement.

## Run it on Windows

1. Use Java 21 and the existing Asterdium folder.
2. Run `Build-Asterdium-Windows.cmd` once if the pinned Gradle 8.10.2 tools
   have not yet been installed.
3. Run **`Run-Vulkan-Smoke-Windows.cmd`** in the project's root folder.
4. The window should show a pixel-textured quad rotating on a
   dark teal background. Close it with Escape or the X.

### Known-good single-frame fallbacks

The original direct-transfer clear is retained for troubleshooting. In a
Command Prompt opened in the repository root, run:

```cmd
set ASTERDIUM_RENDER_MODE=clear
Run-Vulkan-Smoke-Windows.cmd
```

The previously tested 0.0.4 animated triangle is still available:

```cmd
set ASTERDIUM_RENDER_MODE=triangle
Run-Vulkan-Smoke-Windows.cmd
```

The previous static 0.0.3 triangle is also available:

```cmd
set ASTERDIUM_RENDER_MODE=static
Run-Vulkan-Smoke-Windows.cmd
```

These change only that Command Prompt session. Run
`set ASTERDIUM_RENDER_MODE=` to return to the textured quad. In the VS Code
**PowerShell** terminal, set a mode with `$env:ASTERDIUM_RENDER_MODE = "static"`
or clear it with `Remove-Item Env:ASTERDIUM_RENDER_MODE`.
The clear-only path still uses the tested direct transfer if supported.

The continuous loop prefers **FIFO** presentation for display pacing;
static/clear modes retain their tested **IMMEDIATE** preference. Set
`ASTERDIUM_PRESENT_MODE=immediate`, `mailbox` or `fifo` to override. The program
prints the GPU, mode, swapchain image count and first-frame submit/present
status. The displayed frame rate measures the standalone presentation loop,
not GPU execution time and certainly not Minecraft FPS.

## Limitations

- 0.0.5's texture upload, descriptor binding, and indexed drawing need testing
  on Windows; switch to `triangle` if the quad does not render.
- Fixed-size window; no swapchain recreation or multi-frame-in-flight yet.
- This uses a tiny generated demonstration texture, not Minecraft's texture
  atlas or chunk meshes. There is no transparency, depth buffer, or Minecraft
  integration yet.
- The Minecraft 1.21.1 Fabric mod in the parent project remains unchanged.

If the triangle is absent or the program fails, send the first `Validation
Error`/`VUID` line if the Vulkan SDK's validation layer is enabled, along
with the `[frame 1]` / `[loop]` console lines. The validated `triangle`,
`static` and `clear` modes help distinguish upload/descriptor regressions.
