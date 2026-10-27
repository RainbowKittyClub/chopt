package club.rainbowkitty.chopt.ledger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import club.rainbowkitty.kalic0re.common.positiondata.Presence;

/** Whether a log was there before any player touched the world. */
public final class Provenance {
    private Provenance() {}

    /**
     * True only when the position is loaded and not in the ledger. A log in an unloaded chunk, or
     * one asked about off the server thread, is not natural: nothing may be felled on a guess.
     */
    public static boolean isNatural(Level level, BlockPos pos) {
        return PlacedLogs.marks().presence(level, pos) == Presence.ABSENT;
    }
}
