package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Global fluid picker for Terrain Diffusion noise settings. Fluid levels are vanilla's; the wrapper marks the
 * generator so the aquifer mixin can fill river channels and lakes (see {@link #surfaceWater}).
 */
public final class TerrainDiffusionFluidPicker implements Aquifer.FluidPicker {
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    private final Aquifer.FluidPicker delegate;
    private final ThreadLocal<Tile> lastTile = new ThreadLocal<>();

    private record Tile(int startX, int startZ, HeightmapData data) {}

    public TerrainDiffusionFluidPicker(Aquifer.FluidPicker delegate) {
        this.delegate = delegate;
    }

    public static boolean isTerrainDiffusion(NoiseGeneratorSettings settings) {
        boolean[] found = {false};
        settings.noiseRouter().preliminarySurfaceLevel().mapAll(f -> {
            if (f instanceof TerrainDiffusionDensityFunction) found[0] = true;
            return f;
        });
        return found[0];
    }

    @Override
    public Aquifer.FluidStatus computeFluid(int x, int y, int z) {
        return delegate.computeFluid(x, y, z);
    }

    /** Water for open space above a river or lake bed up to its surface; null elsewhere (caves below stay dry). */
    public BlockState surfaceWater(int x, int y, int z) {
        int shift = Integer.numberOfTrailingZeros(TerrainDiffusionConfig.tileSize());
        int startX = (x >> shift) << shift, startZ = (z >> shift) << shift;
        Tile tile = lastTile.get();
        if (tile == null || tile.startX != startX || tile.startZ != startZ) {
            int size = 1 << shift;
            tile = new Tile(startX, startZ, LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX, startZ + size, startX + size));
            lastTile.set(tile);
        }
        HeightmapData data = tile.data;
        if (data == null || data.waterLevel == null || y > data.maxWaterLevel) return null;
        int lx = x - startX, lz = z - startZ;
        short surface = data.waterLevel[lz * data.width + lx];
        if (surface == LocalTerrainProvider.NO_WATER || y > surface) return null;
        if (y <= HeightConverter.convertToMinecraftHeight(data.heightmap[lz][lx])) return null;
        return WATER;
    }
}
