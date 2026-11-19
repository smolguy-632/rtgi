# RT GI (1.12.2, Forge / Cleanroom, client only)

GPU voxel ray-traced global illumination.

* Algorithm: sparse **64-tree** (4x4x4 children, 64-bit masks + popcount) traversed with a hierarchical
  **Amanatides & Woo DDA** in an **OpenCL** kernel (`assets/rtgi/rtgi.cl`) - the exact kernel that won your benchmark
  (`refreshticks.py`: 0.35 ms sphere / 0.73 ms terrain on an Intel UHD 610, 0 mismatches vs flat DDA).
* **1 ray per exposed surface voxel** (the exposed face most facing the camera), perfect mirror reflection,
  light attenuated by 1/max(1,d^2). Emitters = any block with light level > 0 (torch, glowstone...). Misses add a little sky colour.
* **Not every tick**: world->voxel mirroring is time-sliced (`scanSectionsPerTick`), a GI pass starts every
  `refreshIntervalTicks` (default 10) on a background thread and never overlaps the previous pass.
  The render thread only uploads the finished VBO.
* **Celeritas**: no mixins, no hooks into chunk rendering. The result is drawn as additive quads in `RenderWorldLastEvent`.
* OpenCL via **JOCL 2.0.5** (shaded in, NOT relocated - JNI names). Works on Forge/LWJGL2 and Cleanroom/LWJGL3.

## Build
```
JDK 17+ to run Gradle (toolchain builds for Java 8)
./gradlew build          # jar: build/libs/rtgi-1.0.0.jar
./gradlew runClient
```
Config: `config/rtgi.cfg` (in-game: Mods > RT GI > Config is NOT wired; edit file).

## NOT verified (I could not compile/run against real Minecraft)
* `IWorldEventListener` method list, `VertexBuffer`/`GlStateManager` method names (MCP names from memory).
* JOCL native loading inside the mod classloader, shadow plugin packaging.
* Visual tuning (strength, sky) - it is an additive overlay on top of vanilla light.
