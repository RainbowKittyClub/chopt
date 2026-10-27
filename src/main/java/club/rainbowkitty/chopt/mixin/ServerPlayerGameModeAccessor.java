package club.rainbowkitty.chopt.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

/**
 * Vanilla's mining state, which a chop must keep clear of: while {@code isDestroyingBlock} is
 * set, 26.3 sends a hit event every tick, and a delayed destroy would let vanilla break a log on
 * its own. Read to see where a player is mining, and cleared where that is a felling log.
 */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {
    /** Whether vanilla thinks the player is mining. */
    @Accessor("isDestroyingBlock")
    boolean chopt$isDestroyingBlock();

    /** Sets whether vanilla thinks the player is mining. */
    @Accessor("isDestroyingBlock")
    void chopt$setDestroyingBlock(boolean destroying);

    /** The block vanilla thinks the player is mining. */
    @Accessor("destroyPos")
    BlockPos chopt$destroyPos();

    /** Whether vanilla has a delayed break pending. */
    @Accessor("hasDelayedDestroy")
    boolean chopt$hasDelayedDestroy();

    /** Sets whether vanilla has a delayed break pending. */
    @Accessor("hasDelayedDestroy")
    void chopt$setHasDelayedDestroy(boolean delayed);

    /** The block of vanilla's pending delayed break. */
    @Accessor("delayedDestroyPos")
    BlockPos chopt$delayedDestroyPos();
}
