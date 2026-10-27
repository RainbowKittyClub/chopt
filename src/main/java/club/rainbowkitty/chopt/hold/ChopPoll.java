package club.rainbowkitty.chopt.hold;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;

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

import club.rainbowkitty.chopt.ChoptSettings;
import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.chop.ChopTool;
import club.rainbowkitty.chopt.chop.Feller;
import club.rainbowkitty.chopt.mixin.ServerPlayerGameModeAccessor;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;
import club.rainbowkitty.chopt.tree.TimeCurve;

/**
 * Advances every chop once a tick. A player chops while they are swinging at a felling log within
 * reach: vanilla's own hold flags cannot say so, since after a client-predicted break the client
 * sends no new start and keeps punching. Each chopping player adds to the log's progress in
 * proportion to how fast their tool breaks it, and the log moves to its next phase, or falls, when
 * a phase is full.
 */
public final class ChopPoll {
    /** One phase, in the fixed-point units progress is kept in. */
    public static final int UNIT = 65536;

    private ChopPoll() {}

    /** Hooks the poll and the events that end a hold. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ChopPoll::tick);
        ServerPlayerEvents.LEAVE.register(player -> ChopHolds.flush(player.getUUID()));
        ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) ->
                ChopHolds.flush(old.getUUID()));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damage) -> {
            if (entity instanceof ServerPlayer player) {
                ChopHolds.flush(player.getUUID());
            }
            return true;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ChopHolds.flushAll());
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            visit(server, player, now);
        }
        ChopHolds.expire(now, ChoptSettings.CHARGE_GRACE_TICKS.get(server));
    }

    private static void visit(MinecraftServer server, ServerPlayer player, long now) {
        ServerLevel level = player.level();
        Hold held = ChopHolds.of(player);
        if (held != null && hardStop(player, held, level)) {
            ChopHolds.flush(player.getUUID());
        }
        boolean chops = player.isAlive() && ChopTool.chops(player);
        if (chops) {
            clearVanillaMining(player, level);
        }
        BlockPos target = chops && swinging(player) ? aimedFelling(level, player) : null;
        Hold hold = ChopHolds.of(player);
        FellingLogBlockEntity master = target == null ? null : masterOf(level, target);
        if (master == null) {
            if (hold != null) {
                hold.markQuiet(now);
            }
            return;
        }
        BlockPos masterPos = masterPos(level, target, master);
        if (hold == null || !hold.master().equals(masterPos) || hold.level() != level) {
            ChopHolds.flush(player.getUUID());
            InteractionResult asked = AttackBlockCallback.EVENT.invoker().interact(player, level,
                    InteractionHand.MAIN_HAND, target, Direction.UP);
            if (asked == InteractionResult.FAIL) {
                return;
            }
            hold = ChopHolds.start(player, level, masterPos, now);
        }
        credit(server, player, level, hold, masterPos, master, now);
    }

    // Adds one tick of this player's work to the log, and moves it on when a phase is full.
    private static void credit(MinecraftServer server, ServerPlayer player, ServerLevel level,
            Hold hold, BlockPos masterPos, FellingLogBlockEntity master, long now) {
        double base = master.originalOrOak().getDestroyProgress(player, level, masterPos);
        double rest = TimeCurve.m(server, master.logsAbove()) - 1.0;
        long units = base <= 0.0 ? 0L : rest <= 0.0 ? UNIT
                : Math.min(UNIT, (long) Math.ceil(UNIT * master.phases() * base / rest));
        hold.earn(units, player.getMainHandItem());
        master.setLastHit(now);
        if (now - hold.lastCharge() >= ChoptSettings.CHARGE_MAX_DEFER_TICKS.get(server)) {
            ChopHolds.charge(hold, now);
        }
        master.setProgress((int) Math.min(master.progress() + units, 2L * UNIT - 1));
        if (master.progress() < UNIT) {
            return;
        }
        BlockState state = level.getBlockState(masterPos);
        if (state.getValue(FellingLogBlock.PHASE) < master.phases()) {
            master.setProgress(master.progress() - UNIT);
            FellingLogBlock.advance(level, masterPos, master);
        } else {
            Feller.fell(level, masterPos, player);
        }
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

    // A stop that cannot wait for the grace period: the tool is no longer the one in hand, the
    // player left the level, died or is out of survival, or the log is gone.
    private static boolean hardStop(ServerPlayer player, Hold hold, ServerLevel level) {
        return !player.isAlive() || hold.level() != level || !player.gameMode.isSurvival()
                || player.getMainHandItem() != hold.tool() || !isFelling(level, hold.master());
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

    // The block entity of the log, whose progress is the group's for a quarter group's cell.
    private static FellingLogBlockEntity masterOf(ServerLevel level, BlockPos cell) {
        if (!(level.getBlockEntity(cell) instanceof FellingLogBlockEntity entity)
                || entity.original() == null) {
            return null;
        }
        if (entity.family() != Family.QUARTER) {
            return entity;
        }
        BlockPos masterPos = masterPos(level, cell, entity);
        return level.getBlockEntity(masterPos) instanceof FellingLogBlockEntity found
                ? found : null;
    }

    // The cell that holds the progress: the log itself, or a quarter group's northwest cell.
    private static BlockPos masterPos(ServerLevel level, BlockPos cell,
            FellingLogBlockEntity entity) {
        Stageable.Corner corner = entity.family() == Family.QUARTER && entity.original() != null
                ? Stageable.barkSide(entity.original()) : null;
        return corner == null ? cell : Stageable.quarterCells(cell, corner).get(0);
    }
}
