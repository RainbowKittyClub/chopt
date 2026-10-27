package club.rainbowkitty.chopt.ledger;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.Chopt;
import club.rainbowkitty.kalic0re.common.positiondata.PositionMarks;

/**
 * The ledger of logs a player or anything else placed. A natural or sapling-grown log never goes
 * through {@code BlockItem.place}, so it is never marked; a mark survives stripping, because the
 * block is still a log, and goes when the log does.
 */
public final class PlacedLogs {
    private static PositionMarks marks;

    private PlacedLogs() {}

    /** Registers the ledger's kind. Call once from the mod initializer. */
    public static void register() {
        marks = PositionMarks.register(Chopt.id("placed_logs"), state -> state.is(BlockTags.LOGS));
    }

    /** The ledger's marks. */
    public static PositionMarks marks() {
        return marks;
    }

    /** Marks {@code pos} when {@code state}, just placed there, is a log. */
    public static void placed(Level level, BlockPos pos, BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            marks.mark(level, pos);
        }
    }
}
