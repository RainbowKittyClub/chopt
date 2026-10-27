package club.rainbowkitty.chopt.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.kalic0re.common.positiondata.PositionDataHooks;

/** Carries the ledger's marks along with the blocks a piston moves. */
@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockMixin {
    // Takes every pushed block's marks once vanilla has copied their states, before any block is
    // set or broken.
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;"
                    + "getToDestroy()Ljava/util/List;"))
    private List<BlockPos> chopt$take(PistonStructureResolver resolver,
            Operation<List<BlockPos>> original, @Local(argsOnly = true) Level level,
            @Share("chopt$carry") LocalRef<PositionDataHooks.Carry> carry) {
        List<BlockPos> toPush = resolver.getToPush();
        carry.set(PositionDataHooks.take(level, toPush, resolver.getPushDirection()));
        return original.call(resolver);
    }

    // Puts them back once each destination is a moving piston, which keeps them until the block
    // lands.
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/MovingPistonBlock;"
                    + "newMovingBlockEntity(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/core/Direction;ZZ)"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity chopt$land(BlockPos pos, BlockState movingState, BlockState movedState,
            Direction direction, boolean extending, boolean isSourcePiston,
            Operation<BlockEntity> original, @Local(argsOnly = true) Level level,
            @Share("chopt$carry") LocalRef<PositionDataHooks.Carry> carry) {
        if (!isSourcePiston && carry.get() != null) {
            carry.get().land(level, pos, movedState);
        }
        return original.call(pos, movingState, movedState, direction, extending, isSourcePiston);
    }
}
