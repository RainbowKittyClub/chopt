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

/**
 * The holds in progress, one per player. A hold is kept when its player looks at another log, and
 * ends when they have been quiet too long or a hard stop flushes it.
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

    /** Ends {@code player}'s hold, saving the log it was on. */
    public static void end(UUID player) {
        Hold hold = HOLDS.remove(player);
        if (hold == null) {
            return;
        }
        if (hold.level().getBlockEntity(hold.master()) instanceof FellingLogBlockEntity log) {
            log.save();
        }
    }

    /** Ends every hold; used when the server stops. */
    public static void endAll() {
        for (UUID player : new ArrayList<>(HOLDS.keySet())) {
            end(player);
        }
    }

    /** Ends the holds that have been quiet for {@code grace} ticks. */
    static void expire(long now, int grace) {
        List<UUID> over = new ArrayList<>();
        for (Hold hold : HOLDS.values()) {
            if (hold.quietSince() >= 0L && now - hold.quietSince() >= grace) {
                over.add(hold.player());
            }
        }
        over.forEach(ChopHolds::end);
    }
}
