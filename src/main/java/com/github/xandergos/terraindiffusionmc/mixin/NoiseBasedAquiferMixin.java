package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionFluidPicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fills Terrain Diffusion river channels and lakes, which vanilla's 16-block aquifer grid is too coarse to place. */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin {
    @Shadow
    @Final
    private Aquifer.FluidPicker globalFluidPicker;

    @Shadow
    private boolean shouldScheduleFluidUpdate;

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusionMc$surfaceWater(DensityFunction.FunctionContext context, double density, CallbackInfoReturnable<BlockState> cir) {
        if (density > 0 || !(globalFluidPicker instanceof TerrainDiffusionFluidPicker picker)) return;
        BlockState water = picker.surfaceWater(context.blockX(), context.blockY(), context.blockZ());
        if (water != null) {
            shouldScheduleFluidUpdate = true; // let water step down between river sections
            cir.setReturnValue(water);
        }
    }
}
