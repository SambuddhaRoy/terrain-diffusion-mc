package com.github.xandergos.terraindiffusionmc.compat;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.worldgen.IExtendedBiomeSource;
import terrablender.worldgen.IExtendedParameterList;

/**
 * Gives Terrain Diffusion worlds TerraBlender's overworld regions (Biomes O' Plenty and friends).
 * TerraBlender only does this itself for vanilla's MultiNoiseBiomeSource. Only load this class when
 * the terrablender mod is present.
 */
public final class TerraBlenderCompat {
    private TerraBlenderCompat() {
    }

    /** Returns a TerraBlender-initialized copy of {@code parameters} and registers its region biomes with {@code source}. */
    @SuppressWarnings("unchecked")
    public static Climate.ParameterList<Holder<Biome>> initialize(Climate.ParameterList<Holder<Biome>> parameters,
                                                                    BiomeSource source, RegistryAccess registryAccess, long seed) {
        Climate.ParameterList<Holder<Biome>> copy = ((IExtendedParameterList<Holder<Biome>>) (Object) parameters).clone();
        ((IExtendedParameterList<Holder<Biome>>) (Object) copy).initializeForTerraBlender(registryAccess, RegionType.OVERWORLD, seed);

        Registry<Biome> biomes = registryAccess.lookupOrThrow(Registries.BIOME);
        ImmutableList.Builder<Holder<Biome>> regionBiomes = ImmutableList.builder();
        Regions.get(RegionType.OVERWORLD).forEach(region -> region.addBiomes(biomes, entry -> {
            if (biomes.containsKey(entry.getSecond())) regionBiomes.add(biomes.getOrThrow(entry.getSecond()));
        }));
        ((IExtendedBiomeSource) source).appendDeferredBiomesList(regionBiomes.build());
        return copy;
    }

    /** Region-aware lookup; quart coordinates, like BiomeSource.getNoiseBiome. */
    @SuppressWarnings("unchecked")
    public static Holder<Biome> findValue(Climate.ParameterList<Holder<Biome>> parameters, Climate.TargetPoint target,
                                          int quartX, int quartY, int quartZ) {
        return ((IExtendedParameterList<Holder<Biome>>) (Object) parameters).findValuePositional(target, quartX, quartY, quartZ);
    }
}
