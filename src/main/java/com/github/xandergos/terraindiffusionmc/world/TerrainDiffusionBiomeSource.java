package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.compat.TerraBlenderCompat;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terrain-diffusion-mc", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terrain-diffusion-mc", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terrain-diffusion-mc", "snowy_taiga_sparse"));
    // dry treeless grassland; used to be an override of minecraft:grove, which leaked into every world
    private static final FastNoiseLite LUSH_NOISE = caveNoise(1357);
    private static final FastNoiseLite DRIPSTONE_NOISE = caveNoise(2468);

    private static FastNoiseLite caveNoise(int seed) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency(1f / 400f);
        return noise;
    }

    private static final ResourceKey<Biome> STEPPE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terrain-diffusion-mc", "steppe"));

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME),
                    RegistryOps.retrieveElement(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    private HolderGetter<Biome> biomeLookup;
    private final Holder<MultiNoiseBiomeSourceParameterList> overworldParameters;
    private final boolean classic = TerrainDiffusionConfig.classicBiomes();
    /** TerraBlender-initialized copy of the overworld parameters, once the level is loaded (null without TerraBlender). */
    private volatile Climate.ParameterList<Holder<Biome>> terraBlenderParameters;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup, Holder<MultiNoiseBiomeSourceParameterList> overworldParameters) {
        this.biomeLookup = biomeLookup;
        this.overworldParameters = overworldParameters;
    }

    /** Called when the level using this source loads; hooks in TerraBlender regions if that mod is installed. */
    public void initializeForLevel(RegistryAccess registryAccess, long seed) {
        if (!classic && terraBlenderParameters == null && FabricLoader.getInstance().isModLoaded("terrablender")) {
            terraBlenderParameters = TerraBlenderCompat.initialize(overworldParameters.value().parameters(), this, registryAccess, seed);
        }
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap == null) {
            biomeIdMap = Map.ofEntries(
                    entry((short) 1, this.biomeLookup.getOrThrow(Biomes.PLAINS)),
                    entry((short) 3, this.biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS)),
                    entry((short) 5, this.biomeLookup.getOrThrow(Biomes.DESERT)),
                    entry((short) 6, this.biomeLookup.getOrThrow(Biomes.SWAMP)),
                    entry((short) 8, this.biomeLookup.getOrThrow(Biomes.FOREST)),
                    entry((short) 15, this.biomeLookup.getOrThrow(Biomes.TAIGA)),
                    entry((short) 16, this.biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA)),
                    entry((short) 17, this.biomeLookup.getOrThrow(Biomes.SAVANNA)),
                    entry((short) 19, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS)),
                    entry((short) 23, this.biomeLookup.getOrThrow(Biomes.JUNGLE)),
                    entry((short) 26, this.biomeLookup.getOrThrow(Biomes.BADLANDS)),
                    entry((short) 29, this.biomeLookup.getOrThrow(Biomes.MEADOW)),
                    entry((short) 31, this.biomeLookup.getOrThrow(STEPPE)),
                    entry((short) 32, this.biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES)),
                    entry((short) 33, this.biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS)),
                    entry((short) 35, this.biomeLookup.getOrThrow(Biomes.STONY_PEAKS)),
                    entry((short) 41, this.biomeLookup.getOrThrow(Biomes.WARM_OCEAN)),
                    entry((short) 44, this.biomeLookup.getOrThrow(Biomes.OCEAN)),
                    entry((short) 46, this.biomeLookup.getOrThrow(Biomes.COLD_OCEAN)),
                    entry((short) 48, this.biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN)),
                    entry((short) 108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
                    entry((short) 115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
                    entry((short) 116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE))
            );
        }
    }

    @Override
    public Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        if (classic) return biomeIdMap.values().stream();
        return overworldParameters.value().parameters().values().stream().map(Pair::getSecond).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();
        Holder<Biome> defaultEntry = biomeIdMap.get((short) 1);

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        if (data != null && !classic && data.climate != null) {
            int quartsWide = BiomeClassifier.quarts(data.width);
            int qx = Math.max(0, Math.min(quartsWide - 1, (blockX - blockStartX) >> 2));
            int qz = Math.max(0, Math.min(BiomeClassifier.quarts(data.height) - 1, (blockZ - blockStartZ) >> 2));
            int q = (qz * quartsWide + qx) * BiomeClassifier.CLIMATE_PARAMS;
            short[] p = data.climate;
            int surfaceY = HeightConverter.convertToMinecraftHeight(data.heightmap[qz << 2][qx << 2]);
            // vanilla depth: 0 at the surface, +1 per 128 blocks below it (drives cave biomes and deep dark)
            float depthF = (surfaceY - QuartPos.toBlock(y)) / 128f;
            long humidity = p[q + 1], continentalness = p[q + 2];
            if (depthF >= 0.2f) {
                // Real-world climate rarely reaches the very wet / far-inland values vanilla wants for lush and
                // dripstone caves, so give underground patches of both (surface biomes are untouched).
                humidity = Math.max(humidity, Climate.quantizeCoord(LUSH_NOISE.GetNoise(blockX, blockZ) * 0.8f));
                continentalness = Math.max(continentalness, Climate.quantizeCoord(DRIPSTONE_NOISE.GetNoise(blockX, blockZ) * 0.8f));
            }
            Climate.TargetPoint target = new Climate.TargetPoint(p[q], humidity, continentalness, p[q + 3], Climate.quantizeCoord(depthF), p[q + 4]);
            Climate.ParameterList<Holder<Biome>> tb = terraBlenderParameters;
            return tb != null ? TerraBlenderCompat.findValue(tb, target, x, y, z)
                    : overworldParameters.value().parameters().findValue(target);
        }
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            Holder<Biome> entry = biomeIdMap.get(data.biomeIds[localZ][localX]);
            if (entry != null) return entry;
        }

        return defaultEntry;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}

