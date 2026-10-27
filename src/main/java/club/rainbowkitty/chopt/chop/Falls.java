package club.rainbowkitty.chopt.chop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Trees coming down. The cut itself goes at once; the logs that hung from it follow one layer at a
 * time, lowest first, a couple of ticks apart, each layer with the sound and chips of a break.
 * Which logs fall, and how many the tool paid for, is settled before a fall starts.
 */
public final class Falls {
    // Ticks between one layer of a tree and the next.
    private static final int LAYER_TICKS = 2;

    private static final List<Fall> ACTIVE = new ArrayList<>();

    private Falls() {}

    /** Hooks the tick that runs the falls, and finishes any that are left when the server stops. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Falls::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(Falls::finishAll);
    }

    /**
     * Starts the fall of a tree: {@code cluster} comes down now and the rest of {@code members}
     * after it, never more than {@code allowed} logs in all. A cut cell that does not fall is put
     * back as the log it was.
     */
    static void start(ServerLevel level, ServerPlayer breaker, ItemStack tool,
            List<BlockPos> cluster, List<BlockPos> members, int allowed) {
        Fall fall = new Fall(level, breaker.getUUID(), tool, layers(cluster, members), allowed);
        step(fall, breaker);
        for (BlockPos pos : cluster) {
            Feller.restore(level, pos);
        }
        if (!fall.done()) {
            fall.next = level.getServer().getTickCount() + LAYER_TICKS;
            ACTIVE.add(fall);
        }
    }

    // The cut first, then everything else by height.
    private static Deque<List<BlockPos>> layers(List<BlockPos> cluster, List<BlockPos> members) {
        Deque<List<BlockPos>> layers = new ArrayDeque<>();
        layers.add(cluster);
        Map<Integer, List<BlockPos>> byHeight = new TreeMap<>();
        for (BlockPos pos : members) {
            if (!cluster.contains(pos)) {
                byHeight.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(pos);
            }
        }
        layers.addAll(byHeight.values());
        return layers;
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (Iterator<Fall> it = ACTIVE.iterator(); it.hasNext();) {
            Fall fall = it.next();
            if (fall.next > now) {
                continue;
            }
            ServerPlayer breaker = server.getPlayerList().getPlayer(fall.breaker);
            if (breaker == null || breaker.level() != fall.level) {
                it.remove();
                continue;
            }
            step(fall, breaker);
            fall.next = now + LAYER_TICKS;
            if (fall.done()) {
                it.remove();
            }
        }
    }

    // Brings every fall to its end at once, so no tree is left hanging when the server stops.
    private static void finishAll(MinecraftServer server) {
        for (Fall fall : ACTIVE) {
            ServerPlayer breaker = server.getPlayerList().getPlayer(fall.breaker);
            while (breaker != null && !fall.done()) {
                step(fall, breaker);
            }
        }
        ACTIVE.clear();
    }

    // Takes down the next layer. One log of a layer sounds, the rest only throw chips.
    private static void step(Fall fall, ServerPlayer breaker) {
        List<BlockPos> layer = fall.layers.poll();
        if (layer == null) {
            return;
        }
        Cascade.run(() -> {
            boolean sounded = false;
            for (BlockPos pos : layer) {
                if (fall.budget <= 0) {
                    break;
                }
                if (Feller.remove(fall.level, breaker, fall.tool, pos, !sounded)) {
                    fall.budget--;
                    sounded = true;
                }
            }
        });
    }

    // A tree on its way down: the layers still standing, and how many more logs the tool paid for.
    private static final class Fall {
        final ServerLevel level;
        final UUID breaker;
        final ItemStack tool;
        final Deque<List<BlockPos>> layers;
        int budget;
        long next;

        Fall(ServerLevel level, UUID breaker, ItemStack tool, Deque<List<BlockPos>> layers,
                int budget) {
            this.level = level;
            this.breaker = breaker;
            this.tool = tool;
            this.layers = layers;
            this.budget = budget;
        }

        boolean done() {
            return layers.isEmpty() || budget <= 0;
        }
    }
}
