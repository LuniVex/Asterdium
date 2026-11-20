# Asterdium

**Asterdium** is LuniVex's independent, experimental Minecraft renderer project.
It is intended to become an original Vulkan renderer, developed incrementally.

## Current status: 0.0.5 experimental research

This is **not yet a usable Minecraft Vulkan renderer**. The project contains:

1. A Minecraft **1.21.1 Fabric** client-only mod that leaves the normal OpenGL rendering path untouched.
2. An optional **real Vulkan API probe** using LWJGL, which creates a Vulkan instance, enumerates GPUs and graphics-capable queue families, and destroys the instance afterwards.
3. An original CPU-side **16×16×16 chunk section mesher**, with hidden-face culling across section borders, indexed triangle geometry, UVs and normals.
4. Eight self-checks for geometry counts, face winding, determinism and section boundaries.
5. A separate LWJGL 3.4 standalone Windows Vulkan experiment (`VulkanSmoke/`).
   The **0.0.2 clear and 0.0.3 multicolor triangle both rendered on Star's
   RTX 3060**, as did the continuously rotating 0.0.4 triangle. The new
   **0.0.5 experiment** adds an indexed quad and a generated pixel texture
   uploaded into a sampled Vulkan image (pending RTX 3060 validation).

The standalone experiment has a Vulkan logical device, swapchain, and
presentation, but **does not render Minecraft**, upload chunk meshes, or
measure game performance. **No Minecraft FPS claims** should be made.

## Build on Windows

Prerequisites: Java **21 JDK**, internet access, and Windows 10/11.

1. Clone the repository, or use an existing `Asterdium` working folder.
2. Run `Build-Asterdium-Windows.cmd` (the `.ps1` file must remain beside it).
3. The script installs a pinned, SHA-256-verified Gradle 8.10.2 distribution inside `%LOCALAPPDATA%\Asterdium\tools` if necessary, then builds and runs the mesher tests.
4. The development JAR should appear under `build\libs\asterdium-0.0.1-dev.jar`.

This build will download Minecraft development dependencies; its first run can be slow. The output is a **development mod JAR**, not an updated LuniVex launcher and not a Windows `.exe`.

## Test inside Minecraft

This mod is for **Minecraft 1.21.1 + Fabric Loader**. Install Fabric API if your environment's mod setup expects it.

For the optional Vulkan diagnostic, pass the JVM property `-Dasterdium.probe=true` to the Minecraft instance. With the option enabled, `latest.log` will contain lines similar to:

```
[Asterdium] Vulkan loader API: 1.x.y
[Asterdium] Vulkan GPU: NVIDIA GeForce RTX 3060, API 1.x.y, graphics queue family 0
```

The version and GPU name above are **examples**, not benchmark results. Do not treat successful enumeration as a working renderer.

Without that JVM property, the mod simply loads and does nothing to the graphics pipeline. If the probe fails because the Vulkan driver/bindings are missing, it logs a warning and lets the OpenGL game continue.
Run this early diagnostic in a **separate test instance without another Vulkan mod**, because it temporarily initializes and then releases LWJGL's global Vulkan loader.

## Source layout

| Path | Purpose |
| --- | --- |
| `src/main/java/dev/lunivex/asterdium/mesh/` | Original CPU chunk geometry builder |
| `src/main/java/dev/lunivex/asterdium/vulkan/` | Vulkan initialization/hardware discovery |
| `src/main/java/dev/lunivex/asterdium/AsterdiumClient.java` | Safe Fabric entrypoint |
| `src/test/java/` | Deterministic meshing tests |
| `Build-Asterdium-Windows.cmd` | Windows build entrypoint |

## Next milestones

1. **Textured Vulkan geometry**: `VulkanSmoke/` contains the 0.0.5 rotating indexed quad and an original sampled pixel texture. Run `Run-Vulkan-Smoke-Windows.cmd` on Windows. Earlier modes remain available as `triangle`, `static`, or `clear` for regression testing.
2. **Chunk geometry upload**: upload the CPU mesher's geometry and build a block texture atlas.
3. **Minecraft bridge**: design a compatible replacement for the game's OpenGL window creation and renderer, opt-in only, preserving a reversible OpenGL launch path.
4. **Feature completeness**: block states, block atlas, lighting, fluids, transparent blocks, entities, GUI, particles, shader/resource-pack support, mod compatibility.
5. **Benchmarks**: repeatable results on Star's RTX 3060/i9 and future RTX 5080/Ryzen 9900X; verify FPS, 1% lows and GPU/CPU frametimes.

## Intellectual property and release

This public repository contains original Asterdium code and uses Fabric/LWJGL as development dependencies. It does **not** copy VulkanMod, Sodium, OneClient or Mojang's game renderer source. An explicit open-source license has **not yet been selected**; public visibility alone does not grant reuse rights. Before a public binary release, select a license, verify third-party dependency notices, and test the mod on real systems.

## Environment note

The initial source bundle was prepared in a runtime without Gradle or the LWJGL/Fabric/Minecraft dependencies available. The geometry test was compiled and executed using Java 21, but a complete Fabric mod build and live Vulkan GPU probe have **not** been run in that environment. The Windows build script is provided for that validation.

The standalone Vulkan experiment is described in `VulkanSmoke/README.md`.
The 0.0.2 clear, 0.0.3 triangle and 0.0.4 animation all displayed successfully
on Star's RTX 3060; 0.0.5's indexed textured quad still needs an on-device test.
