package club.rainbowkitty.chopt.chop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Brings a tree down when its last phase completes. */
public final class Feller {
    private Feller() {}

    /**
     * The last phase of the log at {@code master} has been completed by {@code finisher}.
     * Filled in by the felling stage.
     */
    public static void fell(ServerLevel level, BlockPos master, ServerPlayer finisher) {
        // The felling stage replaces this; until then a completed log waits at its last phase.
    }
}
