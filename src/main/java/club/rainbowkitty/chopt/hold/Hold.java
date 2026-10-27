package club.rainbowkitty.chopt.hold;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * One player's chop on one felling log: what they have earned toward it and what that has cost
 * their tool. It starts on the first tick a player is credited after a gap, and ends when they
 * stop, with the durability owed charged then, not while they chop.
 */
public final class Hold {
    private final UUID player;
    private final ServerLevel level;
    private final BlockPos master;
    private final long start;
    private ItemStack tool;
    private long credit;
    private int paid;
    private long quietSince = -1L;

    Hold(UUID player, ServerLevel level, BlockPos master, ItemStack tool, long start) {
        this.player = player;
        this.level = level;
        this.master = master;
        this.tool = tool;
        this.start = start;
    }

    /** The player whose hold this is. */
    public UUID player() {
        return player;
    }

    /** The level the log is in. */
    public ServerLevel level() {
        return level;
    }

    /** The log chopped: the master cell of a quarter group, else the log itself. */
    public BlockPos master() {
        return master;
    }

    /** The server tick the hold began, which paces its hit sounds. */
    public long start() {
        return start;
    }

    /** The stack in hand when the hold began or last earned credit. */
    public ItemStack tool() {
        return tool;
    }

    /** Credit earned, in 1/65536 of a phase. */
    public long credit() {
        return credit;
    }

    /** Durability charged for this hold, in blocks. */
    public int paid() {
        return paid;
    }

    void earn(long units, ItemStack held) {
        this.credit += units;
        this.tool = held;
        this.quietSince = -1L;
    }

    void markQuiet(long now) {
        if (quietSince < 0L) {
            quietSince = now;
        }
    }

    /** The tick the player last stopped earning, or -1 while they are earning. */
    public long quietSince() {
        return quietSince;
    }

    void addPaid(int blocks) {
        this.paid += blocks;
    }
}
