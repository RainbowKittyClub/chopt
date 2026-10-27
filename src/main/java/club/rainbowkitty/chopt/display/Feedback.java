package club.rainbowkitty.chopt.display;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
    // Every this many credited ticks a hit sound and a few chips play.
    public static final int HIT_INTERVAL = 4;
    /** Chips thrown by a hit. */
    public static final int HIT_CHIPS = 3;
    /** Chips thrown by a log of a falling tree that does not sound. */
    public static final int FALL_CHIPS = 10;
    // Viewers farther than this from the log are sent no chips.
    private static final double CHIP_RANGE_SQUARED = 32.0 * 32.0;

    private Feedback() {}

    /**
     * The break effect of one phase: particles and the species' break sound. {@code except} is the
     * breaker whose own client already played the break of the log it predicted, or null.
     */
    public static void broke(ServerLevel level, BlockPos pos, BlockState stripped,
            @Nullable ServerPlayer except) {
        level.levelEvent(except, BREAK_EVENT, pos, Block.getId(stripped));
    }

    /** A chopping tick's hit: the species' hit sound, as quiet as a client plays it, and chips. */
    public static void hit(ServerLevel level, BlockPos pos, BlockState stripped) {
        SoundType sound = stripped.getSoundType();
        level.playSound(null, pos, sound.getHitSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F);
        chips(level, pos, stripped, HIT_CHIPS);
    }

    /**
     * {@code count} chips of the stripped log thrown from {@code pos}, silently. Each viewer is
     * sent the block state their own client draws for it: a particle names a state, and a
     * Polymer log's own would not be one their client knows.
     */
    public static void chips(ServerLevel level, BlockPos pos, BlockState stripped, int count) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(x, y, z) <= CHIP_RANGE_SQUARED) {
                level.sendParticles(viewer, new BlockParticleOption(ParticleTypes.BLOCK,
                        shownTo(viewer, stripped)), false, false, x, y, z, count, 0.25, 0.25,
                        0.25, 0.05);
            }
        }
    }

    // The state the viewer's client draws for a block, which for a vanilla block is itself.
    private static BlockState shownTo(ServerPlayer viewer, BlockState state) {
        return PacketContext.supplyWithContext(viewer.connection, () -> {
            PacketContext context = PacketContext.get();
            return PolymerBlockUtils.getPolymerBlockState(breakState(state, context), context);
        });
    }

    // The state whose break effects the viewer is shown; only the RKC Polymer fork has the hook.
    private static BlockState breakState(BlockState state, PacketContext context) {
        //? if >=26.3 {
        return PolymerBlockUtils.getBreakEventBlockState(state, context);
        //?} else {
        /*return state;
        *///?}
    }
}
