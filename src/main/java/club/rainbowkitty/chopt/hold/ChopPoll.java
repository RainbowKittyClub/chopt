package club.rainbowkitty.chopt.hold;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.chop.ChopTool;
import club.rainbowkitty.chopt.chop.Durability;
import club.rainbowkitty.chopt.chop.Feller;
import club.rainbowkitty.chopt.display.Feedback;
import club.rainbowkitty.chopt.mixin.ServerPlayerGameModeAccessor;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;
import club.rainbowkitty.chopt.tree.TimeCurve;

/**
 * Advances every chop once a tick. A player chops while they are swinging at a felling log within
 * reach: vanilla's own hold flags cannot say so, since after a client-predicted break the client
 * sends no new start and keeps punching. Each chopping player works through the current phase on
 * their own, at the rate their tool breaks the log. The first to fill it moves the log to its next
 * phase, or fells the tree, and pays that phase's share of the tool's wear; nobody else's work on
 * that phase counts for anything.
 */
public final class ChopPoll {
    /** One phase, in the fixed-point units progress is kept in. */
    public static final int UNIT = 65536;

    // Ticks a player may look away from a log and come back to it without being asked again.
    private static final int CONTINUE_TICKS = 10;

    private static final Map<UUID, Chop> CHOPS = new HashMap<>();

    private ChopPoll() {}

    /** Hooks the poll and the event that forgets a player who leaves. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ChopPoll::tick);
        ServerPlayerEvents.LEAVE.register(player -> CHOPS.remove(player.getUUID()));
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            visit(server, player, now);
        }
    }

    private static void visit(MinecraftServer server, ServerPlayer player, long now) {
        ServerLevel level = player.level();
        boolean chops = player.isAlive() && ChopTool.chops(player);
        if (chops) {
            clearVanillaMining(player, level);
        }
        BlockPos target = chops && swinging(player) ? aimedFelling(level, player) : null;
        Chopped chopped = target == null ? null : masterOf(level, target);
        if (chopped == null) {
            return;
        }
        BlockPos masterPos = chopped.pos();
        Chop last = CHOPS.get(player.getUUID());
        boolean continuing = last != null && last.level() == level
                && last.master().equals(masterPos) && now - last.tick() <= CONTINUE_TICKS;
        if (!continuing && AttackBlockCallback.EVENT.invoker().interact(player, level,
                InteractionHand.MAIN_HAND, target, Direction.UP) == InteractionResult.FAIL) {
            return;
        }
        CHOPS.put(player.getUUID(), new Chop(level, masterPos, now));
        credit(server, player, level, masterPos, chopped.entity(), now);
    }

    // Adds one tick of this player's work to their own progress on the log, and finishes the phase
    // if that fills it.
    private static void credit(MinecraftServer server, ServerPlayer player, ServerLevel level,
            BlockPos masterPos, FellingLogBlockEntity master, long now) {
        double base = master.originalOrOak().getDestroyProgress(player, level, masterPos);
        double rest = TimeCurve.m(server, master.logsAbove()) - 1.0;
        long units = base <= 0.0 ? 0L : rest <= 0.0 ? UNIT
                : Math.min(UNIT, (long) Math.ceil(UNIT * master.phases() * base / rest));
        master.setLastHit(now);
        if (master.stripped() != null && now % Feedback.HIT_INTERVAL == 0) {
            Feedback.hit(level, masterPos, master.stripped());
        }
        UUID id = player.getUUID();
        long total = master.progressOf(id) + units;
        if (total < UNIT) {
            master.setProgressOf(id, (int) total);
            master.save();
            return;
        }
        finishPhase(level, player, masterPos, master, (int) Math.min(total - UNIT, UNIT - 1));
    }

    // The player has filled the phase: they pay its share of the wear, and the log moves on with
    // everyone's progress cleared but the part of theirs that ran past the end. The last phase
    // fells the tree, where the finisher pays what is left.
    private static void finishPhase(ServerLevel level, ServerPlayer player, BlockPos masterPos,
            FellingLogBlockEntity master, int carried) {
        BlockState state = level.getBlockState(masterPos);
        int phase = state.getValue(FellingLogBlock.PHASE);
        if (phase >= master.phases()) {
            Feller.fell(level, masterPos, player);
            return;
        }
        int logs = master.logsAbove() + 1;
        int owed = Durability.shareThrough(logs, phase, master.phases()) - master.paid();
        if (owed > 0) {
            master.setPaid(master.paid()
                    + Durability.charge(player, player.getMainHandItem(), owed));
        }
        master.startNextPhase(player.getUUID(), carried);
        FellingLogBlock.advance(level, masterPos, master);
    }

    // A player chopping a felling log must not leave vanilla's mining flags pointing at it:
    // while they are set 26.3 repeats a hit event every tick, and a delayed destroy would let
    // vanilla break the log by itself.
    private static void clearVanillaMining(ServerPlayer player, ServerLevel level) {
        ServerPlayerGameModeAccessor mode = (ServerPlayerGameModeAccessor) player.gameMode;
        if (mode.chopt$hasDelayedDestroy() && isFelling(level, mode.chopt$delayedDestroyPos())) {
            mode.chopt$setHasDelayedDestroy(false);
        }
        if (mode.chopt$isDestroyingBlock() && isFelling(level, mode.chopt$destroyPos())) {
            mode.chopt$setDestroyingBlock(false);
        }
    }

    private static boolean isFelling(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos)
                && level.getBlockState(pos).getBlock() instanceof FellingLogBlock;
    }

    // Whether the player is mid-swing, which the client keeps up for as long as it holds the click.
    private static boolean swinging(ServerPlayer player) {
        //? if >=26.3 {
        return player.isSwinging();
        //?} else {
        /*return player.swinging;
        *///?}
    }

    // The felling log under the crosshair within reach, by its outline, or null.
    private static BlockPos aimedFelling(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && isFelling(level, hit.getBlockPos())) {
            return hit.getBlockPos();
        }
        return null;
    }

    /**
     * The cell whose block entity holds the progress of the log that the felling cell {@code cell}
     * belongs to: the cell itself, or for a quarter group the northwest cell, found from the
     * cell's own corner. Null if it cannot be found.
     */
    public static @Nullable BlockPos masterPosOf(ServerLevel level, BlockPos cell) {
        Chopped chopped = masterOf(level, cell);
        return chopped == null ? null : chopped.pos();
    }

    // The log whose progress the aimed cell counts toward, or null.
    private static Chopped masterOf(ServerLevel level, BlockPos cell) {
        if (!(level.getBlockEntity(cell) instanceof FellingLogBlockEntity own)
                || own.original() == null) {
            return null;
        }
        if (own.family() != Family.QUARTER) {
            return new Chopped(cell, own);
        }
        Stageable.Corner corner = Stageable.barkSide(own.original());
        if (corner == null) {
            return null;
        }
        BlockPos northwest = Stageable.quarterCells(cell, corner).get(0);
        return level.getBlockEntity(northwest) instanceof FellingLogBlockEntity master
                ? new Chopped(northwest, master) : null;
    }

    // A log being chopped, and the cell that holds its progress.
    private record Chopped(BlockPos pos, FellingLogBlockEntity entity) {}

    // The log a player was last credited on, and when.
    private record Chop(ServerLevel level, BlockPos master, long tick) {}
}
