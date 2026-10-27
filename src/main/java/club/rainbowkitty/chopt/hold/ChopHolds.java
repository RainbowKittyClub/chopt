package club.rainbowkitty.chopt.hold;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.chop.Durability;

/**
 * The holds in progress, one per player. A hold is kept when its player looks at another log, and
 * ends when they have been quiet too long or a hard stop flushes it. Ending a hold charges the
 * tool for the share of the tree its work has earned.
 */
public final class ChopHolds {
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private ChopHolds() {}

    /** The player's hold, or null. */
    public static Hold of(ServerPlayer player) {
        return HOLDS.get(player.getUUID());
    }

    /** Starts a hold on {@code master} for {@code player}, replacing any other. */
    static Hold start(ServerPlayer player, ServerLevel level, BlockPos master, long now) {
        Hold hold = new Hold(player.getUUID(), level, master, player.getMainHandItem(), now);
        HOLDS.put(player.getUUID(), hold);
        return hold;
    }

    /** Charges what the hold has earned so far and ends it, saving the log. */
    public static void flush(UUID player) {
        Hold hold = HOLDS.get(player);
        if (hold != null) {
            charge(hold, hold.level().getServer().getTickCount());
        }
        end(player);
    }

    /** Ends {@code player}'s hold without charging anything, saving the log. */
    public static void end(UUID player) {
        Hold hold = HOLDS.remove(player);
        if (hold == null) {
            return;
        }
        if (hold.level().getBlockEntity(hold.master()) instanceof FellingLogBlockEntity log) {
            log.save();
        }
    }

    /** Whether any player's hold is on the log at {@code master}. */
    public static boolean heldOn(ServerLevel level, BlockPos master) {
        for (Hold hold : HOLDS.values()) {
            if (hold.level() == level && hold.master().equals(master)) {
                return true;
            }
        }
        return false;
    }

    /** Flushes every hold; used when the server stops. */
    public static void flushAll() {
        for (UUID player : new ArrayList<>(HOLDS.keySet())) {
            flush(player);
        }
    }

    /** Flushes every player's hold on the log at {@code master}, which is about to fall. */
    public static void flushOn(ServerLevel level, BlockPos master) {
        for (Hold hold : new ArrayList<>(HOLDS.values())) {
            if (hold.level() == level && hold.master().equals(master)) {
                flush(hold.player());
            }
        }
    }

    /**
     * Charges the durability a hold has earned and not yet paid: its credit as a share of the
     * tree's logs, less what it has paid. Counted in blocks, from the log count as it was last
     * worked out, and never beyond what the tree still owes.
     */
    public static void charge(Hold hold, long now) {
        if (!(hold.level().getBlockEntity(hold.master()) instanceof FellingLogBlockEntity log)) {
            return;
        }
        long logs = log.logsAbove() + 1L;
        long share = hold.credit() * logs / ((long) log.phases() * ChopPoll.UNIT);
        int owed = (int) Math.max(0L, Math.min(share - hold.paid(), logs - log.paid()));
        if (owed <= 0) {
            return;
        }
        ServerPlayer player = hold.level().getServer().getPlayerList().getPlayer(hold.player());
        int covered = player == null ? owed : Durability.charge(player, hold.tool(), owed);
        hold.addPaid(covered, now);
        log.setPaid(log.paid() + covered);
        log.save();
    }

    /** Flushes the holds that have been quiet for {@code grace} ticks. */
    static void expire(long now, int grace) {
        List<UUID> over = new ArrayList<>();
        for (Hold hold : HOLDS.values()) {
            if (hold.quietSince() >= 0L && now - hold.quietSince() >= grace) {
                over.add(hold.player());
            }
        }
        over.forEach(ChopHolds::flush);
    }
}
