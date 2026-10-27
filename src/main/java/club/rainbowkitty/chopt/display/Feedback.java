package club.rainbowkitty.chopt.display;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The sound and particles of a chop, which the barrier every client sees cannot supply: it hits
 * and breaks as stone. Both go out as the species' own wood, through the stripped log's id, which
 * Polymer rewrites for each viewer.
 */
public final class Feedback {
    private static final int BREAK_EVENT = 2001;
    // Every this many credited ticks a hit sound plays.
    public static final int HIT_INTERVAL = 4;

    private Feedback() {}

    /**
     * The break effect of one phase: particles and the species' break sound. {@code except} is the
     * breaker whose own client already played the break of the log it predicted, or null.
     */
    public static void broke(ServerLevel level, BlockPos pos, BlockState stripped,
            @Nullable ServerPlayer except) {
        level.levelEvent(except, BREAK_EVENT, pos, Block.getId(stripped));
    }

    /** The hit sound of a chopping tick: the species' hit sound, as quiet as a client plays it. */
    public static void hit(ServerLevel level, BlockPos pos, BlockState stripped) {
        SoundType sound = stripped.getSoundType();
        level.playSound(null, pos, sound.getHitSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F);
    }
}
