# Terrain Diffusion Continued

A maintained fork of [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion-mc) by **xandergos** ([Modrinth](https://modrinth.com/mod/terrain-diffusion)), updated for **Minecraft 26.2 (Fabric)** and turned into a survival-ready world generator. All the AI terrain is the original author's work; this fork adds the parts survival players expect from a world generator.

### What's new in Continued

- **Minecraft 26.2**: ported to Fabric Loader 0.19.5+, Fabric API, Java 25.
- **Caves**: vanilla's full cave network (cheese caves, spaghetti and noodle tunnels, pillars, cave entrances) carved beneath the diffusion terrain, with **aquifers** on: dry caves, underground lakes, and lava in the deep.
- **Ores**: vanilla ore distribution plus **large copper and iron ore veins**.
- **Rivers and lakes**: streams and rivers follow the terrain's real drainage, carved into the valleys with widths that grow downstream, and lakes fill closed basins. Computed per tile with no seams; turn off with `rivers.enabled=false` for faster generation.
- **Every biome**: biomes are chosen by feeding Terrain Diffusion's climate and elevation into vanilla's multi-noise biome table, so the world gets all vanilla biomes (cherry groves, dark forests, mangrove swamps, deep oceans, beaches) and the cave biomes underground (lush caves, dripstone caves, deep dark).
- **Structures**: because those biomes exist now, ocean monuments, woodland mansions and ancient cities generate again, alongside villages, outposts, trial chambers and the rest.
- **Mod compatibility**: biomes added to the overworld by datapacks, or by [TerraBlender](https://modrinth.com/mod/terrablender) mods such as **Biomes O' Plenty**, generate in Terrain Diffusion worlds, including their surface blocks. Structure mods that pick biomes by tag work too.
- **Fixes**: a DirectML GPU memory leak that broke terrain after about 30 tiles; the mod no longer overrides vanilla's grove biome in every world; vanilla's surface rules (no grass on cave floors).
- Want the original look? Set `biomes.mode=classic` (see [Configuration](#configuration)).

### Known limitations

- **Rivers cost generation time** (roughly +50% on freshly explored land). Set `rivers.enabled=false` if generation can't keep up.
- **`/locate structure` can be slow** for structures that check ground height (temples, igloos, wells), because terrain has to be generated along the way. `/locate biome` doesn't work in Terrain Diffusion worlds.
- **Voxy SeedGen** deliberately refuses Terrain Diffusion worlds (generating distant terrain from the seed would take hours of GPU time). Voxy on its own, or Voxy World Gen, works from real chunks.

### Credits

- Terrain Diffusion and the original mod: [Alexander Goslin (xandergos)](https://github.com/xandergos), MIT license. Research accepted to SIGGRAPH 2026.
- Continued fork: [SambuddhaRoy](https://github.com/SambuddhaRoy).
- **AI disclosure:** the terrain is generated at runtime by a generative diffusion model, and much of this fork's new code was written with the help of an AI coding assistant (Claude).

---

*The original README follows, with links and requirements updated for this fork.*

This is a Minecraft Fabric mod integrating [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion).

## Which version should I use?

Three builds are available on the [Releases](https://github.com/SambuddhaRoy/terrain-diffusion-mc/releases) page:

**The CPU build is slow unless you are on MacOS.**

| Build                     | Supports                    | Setup required                          |
|---------------------------| --------------------------- | --------------------------------------- |
| **Windows** (recommended) | Windows with any modern GPU | None                                    |
| **CUDA**                  | NVIDIA GPUs                 | [CUDA + cuDNN install](CUDA_INSTALL.md) |
| **CPU**                   | Everything else             | None                                    |

> **Mac users:** the CPU build automatically uses CoreML for hardware acceleration on Apple Silicon. No extra setup is needed.

Use the `-cuda` build only if you are on Linux, or have an NVIDIA GPU and prefer CUDA (may improve performance).

## Requirements

- Minecraft with [Fabric](https://fabricmc.net/) and the [Fabric API Mod](https://modrinth.com/mod/fabric-api) installed
- Windows with a GPU OR Linux with an NVIDIA GPU is strongly recommended. CPU inference works but is very slow.
- VRAM (GPU RAM) needed: 1.5GB
- RAM needed: 2.5GB (May need to increase Minecraft's RAM allocation)

## Usage

**If using CUDA build:** First see [CUDA_INSTALL.md](CUDA_INSTALL.md).

1. Download the mod jar from [Releases](https://github.com/SambuddhaRoy/terrain-diffusion-mc/releases) for your Minecraft version and place it in your Minecraft `mods/` folder. Make sure the Minecraft version matches.
2. Launch Minecraft, at least once online to download the models (~2.5GB).
3. Create a world, and select the **Terrain Diffusion** world type. Click **Customize** to set the `World Scale` (see [Per-world settings](#per-world-settings) below).
4. The mod will search for a land spawn point near the world origin automatically. If the area around (0, 0) is entirely ocean, it may take a moment to find land. Use `/td-explore` (see below) to scout the world further.

## Exploring the World

The mod includes a built-in terrain explorer web UI. Run the `/td-explore` command in-game; it will print a clickable link (e.g. `http://localhost:19801`) that opens an interactive map in your browser. Click the map on the left to open a "detailed view". Click the detailed view to get coordinates in the bottom left. You can also filter for certain climates.

Use the explorer to scout continents, mountains, islands, and other interesting terrain before venturing out in Minecraft.

## Configuration

Edit `config/terrain-diffusion-mc.properties` (created automatically on first launch):

```
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto" (try GPU first then fall back to CPU).
# "gpu" uses DirectML on the -windows build, or CUDA on the -cuda build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free for slightly
# faster generation.
inference.offload_models=true

# Validate SHA-256 for pre-existing files in .minecraft/terrain-diffusion-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/td-explore).
explorer.port=19801

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area (hundreds of blocks), so 16–128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128

# Biome selection.
#   climate: Terrain Diffusion's climate and elevation pick from the full overworld biome list
#            (all vanilla biomes, plus biomes added by datapacks and biome mods).
#   classic: the original fixed palette of 23 biomes.
biomes.mode=climate

# Rivers and lakes above sea level, from drainage computed on the terrain.
# Costs some extra terrain inference per tile; set to false for faster generation.
rivers.enabled=true
```

### Per-world settings

For Terrain Diffusion worlds, click **Customize** in world creation and set:

- `World Scale` (integer `1..6`)

This value is saved with the world save and affects:

- how many real-world meters each block represents (`scale=1` => `30m/block`, `scale=2` => `15m/block`, etc.)
- world max height for newly created worlds (assumes tallest point is 10000 real-world meters)
- 2 is recommended for a good balance of scale and playability. Use 1 for smaller, more compressed worlds.
- Lower values put more stress on the GPU (Terrain Diffusion runs more often), while higher values put more stress on the CPU (larger world height). Most modern GPUs will be bottlenecked by the CPU around scale 2 or 3.

## Common Issues

**A dynamic link library (DLL) initialization routine failed**

This can happen for some older Java versions. Minecraft 26.2 needs Java 25; please update to the latest Java 25.

**LoadLibrary failed with error 126** *(CUDA build only)*

This is typically due to an improper CUDA or cuDNN installation. See [CUDA_INSTALL.md](CUDA_INSTALL.md) for troubleshooting steps.

**java.lang.IllegalStateException: Failed to load terrain-diffusion models**

This typically indicates an "out of memory" error (the logs should show this as well).
Terrain Diffusion's models take up about 2.5GB of RAM, so make sure to allocate enough RAM to account for this.

**If your issue is still not resolved, please [raise it here](https://github.com/SambuddhaRoy/terrain-diffusion-mc/issues/new).**

## Building from Source

An internet connection is required during the build to fetch the pinned model manifest metadata from Hugging Face.

The `-windows` build requires `libs/onnxruntime-dml.jar`, which is provided as part of the repo. See [Building onnxruntime with DirectML](#building-onnxruntime-with-directml) to build from source. 

Build for Windows (DirectML):
```
./gradlew build -PuseDml=true
```

Build for CUDA:
```
./gradlew build -PuseCuda=true
```

Build for CPU (also handles macOS/CoreML automatically):
```
./gradlew build -PuseCpu=true
```

Build all:
```
./gradlew buildAll
```

### Building onnxruntime with DirectML

**Requirements**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — for Windows 10 version 1803 or newer
- Visual Studio 2017 toolchain — install *Desktop development with C++* from the VS Installer
- Visual Studio 2022 toolchain — same as above
- Python 3.10+: [https://python.org/](https://python.org/)
- CMake 3.28 or higher

Keep both VS toolchains up to date. Full details at the [ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the [DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

**Steps**

Run all commands from the **Developer Command Prompt for VS 2022**.

```
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The built jar appears in `java/build/`. Rename it to `onnxruntime-dml.jar` and place it in `libs/` in this repository.

## Note For Mod Developers

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is extremely simple. The model outputs elevation + 4 climate variables, and this is converted to Minecraft biomes with hand-written rules. This is the most immediate way to improve the quality of the terrain and is relatively easy, but takes time to get realistic. The entire biome classifier is [only 250 lines](src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java). In this fork, `writeClimate` in the same file maps the model's climate onto vanilla's multi-noise parameters.

The terrain diversity far outpaces the biome diversity and there's a real opportunity to close that gap. I'm hoping someone goes crazy with it.
