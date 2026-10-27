package club.rainbowkitty.chopt.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import club.rainbowkitty.kalic0re.common.positiondata.PositionDataHooks;

/** Tells the ledger about every block a chunk replaces. */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    // The state replaced, or null when nothing changed; no callback object on this hot path.
    @ModifyReturnValue(method = "setBlockState", at = @At("RETURN"))
    private @Nullable BlockState chopt$dropUnheld(@Nullable BlockState old,
            @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) BlockState state) {
        if (old != null) {
            PositionDataHooks.blockChanged((LevelChunk) (Object) this, pos, old, state);
        }
        return old;
    }
}
