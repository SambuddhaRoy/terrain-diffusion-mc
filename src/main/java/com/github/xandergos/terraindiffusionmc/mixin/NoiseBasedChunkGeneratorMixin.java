package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionFluidPicker;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "createFluidPicker", at = @At("RETURN"), cancellable = true)
    private static void terrainDiffusionMc$markFluidPicker(NoiseGeneratorSettings settings, CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
        if (TerrainDiffusionFluidPicker.isTerrainDiffusion(settings)) {
            cir.setReturnValue(new TerrainDiffusionFluidPicker(cir.getReturnValue()));
        }
    }
}
