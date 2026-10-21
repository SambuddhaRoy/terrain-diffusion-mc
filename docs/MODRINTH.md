# Modrinth listing: Terrain Diffusion Continued

## Project settings

| Field | Value |
|---|---|
| Name | Terrain Diffusion Continued |
| Slug | `terrain-diffusion-continued` |
| Summary | AI-diffusion terrain for 26.2 with caves, ore veins, and every vanilla and modded biome. Continued fork of Terrain Diffusion. |
| Category | Worldgen |
| Client side | Optional (needed for singleplayer and the world-creation screen) |
| Server side | Required |
| License | MIT |
| Source | https://github.com/SambuddhaRoy/terrain-diffusion-mc |
| Issues | https://github.com/SambuddhaRoy/terrain-diffusion-mc/issues |
| **Contains AI-generated content** | **Yes, tick it** (Modrinth rule 6.1: the mod relies on a generative diffusion model, and much of the fork's code was written with an AI assistant) |

Don't use AI-generated images for the icon, gallery or description (rule 6.2). Use real in-game screenshots.

## Version upload

| Field | Value |
|---|---|
| File | `terrain-diffusion-mc-2.3.0-windows+26.2.jar` (`./gradlew build -PuseDml=true`) |
| Version number | `2.3.0-windows+26.2` |
| Game versions | 26.2 |
| Loader | Fabric |
| Dependencies | Fabric API (required), TerraBlender (optional) |

The CUDA and CPU builds go on GitHub Releases only, like upstream (the CUDA jar is about 560 MB).

## Description

Paste everything below the line.

---

**Terrain Diffusion Continued** is a maintained fork of [Terrain Diffusion](https://modrinth.com/mod/terrain-diffusion) by **xandergos**, updated for Minecraft 26.2 and made ready for survival.

Terrain Diffusion replaces Minecraft's noise terrain with terrain from a diffusion model trained on real-world elevation data: continents, mountain ranges, river-carved valleys and coastlines that look like the real thing, generated infinitely. All of that is the original author's work (published at SIGGRAPH 2026). This fork adds what a survival world needs underneath and around it.

## What's new in Continued

- **Minecraft 26.2** (Fabric Loader 0.19.5+, Java 25)
- **Caves**: vanilla's full cave network (cheese caves, spaghetti and noodle tunnels, pillars, cave entrances) under the diffusion terrain, with **aquifers**: dry caves, underground lakes, lava in the deep
- **Ores**: vanilla ores plus large **copper and iron ore veins**
- **Rivers and lakes**: streams and rivers follow the terrain's real drainage, widening downstream, and lakes fill closed basins
- **Every biome**: Terrain Diffusion's climate and elevation now choose from vanilla's full biome table, so you get cherry groves, dark forests, mangrove swamps, deep oceans and beaches, plus lush caves, dripstone caves and the deep dark underground
- **Structures**: ocean monuments, woodland mansions and ancient cities generate again, along with villages, outposts and trial chambers
- **Mod compatibility**: biomes from datapacks and from TerraBlender mods such as **Biomes O' Plenty** generate in Terrain Diffusion worlds, with their surface blocks; structure mods that place by biome tag work too
- **Fixes**: a GPU memory leak (DirectML) that broke terrain generation after about 30 tiles; the mod no longer changes vanilla's grove biome in every world
- Prefer the original look? Set `biomes.mode=classic` in `config/terrain-diffusion-mc.properties`

## Requirements

- Fabric API
- Windows with a modern GPU (DirectML). For NVIDIA CUDA, Linux, or CPU/macOS builds, see [GitHub Releases](https://github.com/SambuddhaRoy/terrain-diffusion-mc/releases)
- About 1.5 GB of VRAM and 2.5 GB of RAM for the models
- The first launch downloads the models (about 2.1 GB) from Hugging Face

## Usage

Create a world and pick the **Terrain Diffusion** world type. **Customize** sets the world scale (2 is a good default). In game, `/td-explore` opens a map of your world in the browser.

## Known limitations

- Rivers add roughly 50% to generation time on new land; `rivers.enabled=false` turns them off.
- `/locate structure` can take minutes for structures that check ground height (temples, igloos). `/locate biome` doesn't work in Terrain Diffusion worlds.
- Voxy SeedGen doesn't support Terrain Diffusion worlds. Plain Voxy, or Voxy World Gen, works.

## Credits

- [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion-mc) by Alexander Goslin (xandergos), MIT license
- Continued by [SambuddhaRoy](https://github.com/SambuddhaRoy)
- This mod generates terrain with a generative AI model, and much of this fork's code was written with the help of an AI coding assistant.
