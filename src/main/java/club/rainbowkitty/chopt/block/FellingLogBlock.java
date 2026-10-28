package club.rainbowkitty.chopt.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import club.rainbowkitty.chopt.ChoptSettings;
import club.rainbowkitty.chopt.chop.ChopTool;
import club.rainbowkitty.chopt.display.Feedback;
import club.rainbowkitty.chopt.display.FellingHolder;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Region;
import club.rainbowkitty.chopt.tree.Stageable;
import club.rainbowkitty.chopt.tree.Strip;

/**
 * A log being felled. To every client it is a barrier, so its outline and collision never change
 * as the phases go by; the shrinking log is a display inside it (S7). The block entity holds the
 * log it was. A vanilla break of this block gives that log back, so a sneaking player or another
 * mod breaks it as the plain log it was; any other player in survival chops it. A cell of a 2x2
 * group is the exception: it is chopped even while sneaking, and a plain break is refused
 * ({@code GroupBreaks}).
 */
public class FellingLogBlock extends BarrierBlock
        implements PolymerBlock, EntityBlock, BlockWithElementHolder {
    /** The current phase, from 1; the northwest cell of a group holds the group's. */
    public static final IntegerProperty PHASE = IntegerProperty.create("phase", 1, 15);
    /** The axis the display runs along, which the log's own axis is for a plain log. */
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    private static final BlockState BARRIER = Blocks.BARRIER.defaultBlockState();
    private static final BlockState BARRIER_WET = BARRIER.setValue(WATERLOGGED, true);
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    private static final Queue<Awaiting> AWAITING_LOAD = new ConcurrentLinkedQueue<>();
    // Felling logs beside one that was just removed, to check before the tick ends.
    private static final Queue<Awaiting> RECHECK = new ConcurrentLinkedQueue<>();
    // How many ticks a loaded log waits for its chunk to tick before it is left to its idle check.
    private static final int MAX_LOAD_WAIT = 600;
    // Ticks between checks of a felling log that is not yet idle long enough to revert.
    private static final long IDLE_RECHECK = 200L;

    // A loaded felling log whose chunk is not ticking yet.
    private record Awaiting(ServerLevel level, BlockPos pos, int tries) {}

    /** The data a new felling log is built with, handed to its block entity while it is built. */
    public record Pending(BlockPos pos, BlockState original, BlockState stripped, Family family,
            Stageable.@Nullable Corner corner, int phases, int logsAbove) {}

    public FellingLogBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(PHASE, 1)
                .setValue(AXIS, Direction.Axis.Y));
    }

    /**
     * Checks every felling log a chunk loads, which a crash or an edit may have left unsound. The
     * chunk is not ticking yet when its block entities load, and vanilla refuses a scheduled tick
     * there, so each waits in a queue until its chunk is.
     */
    public static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof FellingLogBlockEntity) {
                AWAITING_LOAD.add(new Awaiting(level, entity.getBlockPos(), 0));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            admitLoaded();
            recheckNeighbours();
        });
        // Nothing queued outlives its server, whose levels the entries hold.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            AWAITING_LOAD.clear();
            RECHECK.clear();
        });
    }

    // Checks the cells that stood beside a removed one, now that whatever removed it is done: the
    // rest of a group it broke up goes back to plain logs by the end of that tick, or of the next
    // for a removal made by the tick itself. A scheduled tick would not do, since a pending one of
    // the cell's own, the idle check, swallows a new one.
    private static void recheckNeighbours() {
        for (int waiting = RECHECK.size(); waiting > 0; waiting--) {
            Awaiting next = RECHECK.poll();
            if (next != null && next.level().hasChunkAt(next.pos())) {
                BlockState there = next.level().getBlockState(next.pos());
                if (there.getBlock() == ChoptBlocks.FELLING_LOG) {
                    validate(next.level(), next.pos(), there);
                }
            }
        }
    }

    // Schedules the check of every queued log whose chunk now ticks, and asks again next tick of
    // the rest, for a while: a chunk that never ticks (nobody near) is checked when it does, by
    // the idle tick the log keeps anyway.
    private static void admitLoaded() {
        for (int waiting = AWAITING_LOAD.size(); waiting > 0; waiting--) {
            Awaiting next = AWAITING_LOAD.poll();
            if (next == null || !next.level().hasChunkAt(next.pos())) {
                continue;
            }
            BlockState there = next.level().getBlockState(next.pos());
            if (there.getBlock() != ChoptBlocks.FELLING_LOG) {
                continue;
            }
            if (next.level().isPositionTickingWithEntitiesLoaded(ChunkPos.pack(next.pos()))) {
                next.level().scheduleTick(next.pos(), ChoptBlocks.FELLING_LOG, 1);
            } else if (next.tries() < MAX_LOAD_WAIT) {
                AWAITING_LOAD.add(new Awaiting(next.level(), next.pos(), next.tries() + 1));
            }
        }
    }

    /**
     * Turns the log at {@code pos} into a felling log in phase 1. The stripped look is worked out
     * first, while the log is still there to be read. {@code corner} is where the log sits in its
     * 2x2 group, null for one that stands alone.
     */
    public static void stage(Level level, BlockPos pos, BlockState original, Family family,
            Stageable.@Nullable Corner corner, int phases, int logsAbove) {
        BlockState stripped = Strip.of(level, pos, original);
        BlockState felling = ChoptBlocks.FELLING_LOG.defaultBlockState()
                .setValue(AXIS, displayAxis(original, family))
                .setValue(WATERLOGGED, original.hasProperty(WATERLOGGED)
                        && original.getValue(WATERLOGGED));
        PENDING.set(new Pending(pos, original, stripped, family, corner, phases, logsAbove));
        try {
            level.setBlock(pos, felling, Block.UPDATE_ALL);
        } finally {
            PENDING.remove();
        }
        if (level.getServer() != null
                && level.getBlockEntity(pos) instanceof FellingLogBlockEntity entity) {
            entity.setLastHit(level.getServer().getTickCount());
        }
    }

    /**
     * Turns the four cells of a 2x2 group, northwest first and then clockwise, into felling logs,
     * the northwest cell first so that the others find their master when they check themselves. A
     * failure puts back every cell already turned.
     */
    public static void stageGroup(Level level, List<BlockPos> cells, int phases, int logsAbove) {
        List<BlockState> originals = new ArrayList<>();
        for (BlockPos cell : cells) {
            originals.add(level.getBlockState(cell));
        }
        int done = 0;
        try {
            for (; done < cells.size(); done++) {
                stage(level, cells.get(done), originals.get(done), Family.GROUP,
                        Stageable.Corner.values()[done], phases, logsAbove);
            }
        } catch (RuntimeException e) {
            for (int i = 0; i < done; i++) {
                level.setBlock(cells.get(i), originals.get(i), Block.UPDATE_ALL);
            }
            throw e;
        }
    }

    // The axis a log's display runs along: its own for a plain log, the main axis of a small log
    // (up and down, else east and west, else north-south), and always upright for a group.
    private static Direction.Axis displayAxis(BlockState original, Family family) {
        if (family == Family.GROUP) {
            return Direction.Axis.Y;
        }
        if (Stageable.isSmall(original)) {
            boolean vertical = original.getValue(BlockStateProperties.UP)
                    || original.getValue(BlockStateProperties.DOWN);
            if (vertical) {
                return Direction.Axis.Y;
            }
            boolean eastWest = original.getValue(BlockStateProperties.EAST)
                    || original.getValue(BlockStateProperties.WEST);
            return eastWest ? Direction.Axis.X : Direction.Axis.Z;
        }
        return original.hasProperty(AXIS) ? original.getValue(AXIS) : Direction.Axis.Y;
    }

    /** The data of the conversion staging {@code pos} on this thread, or null. */
    public static @Nullable Pending pendingAt(BlockPos pos) {
        Pending pending = PENDING.get();
        return pending != null && pending.pos().equals(pos) ? pending : null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PHASE, AXIS);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        FellingLogBlockEntity entity = new FellingLogBlockEntity(pos, state);
        Pending pending = PENDING.get();
        if (pending != null && pending.pos().equals(pos)) {
            entity.fill(pending);
        }
        return entity;
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos,
            BlockState initialState) {
        Pending pending = pendingAt(pos);
        boolean follower = pending != null && pending.family() == Family.GROUP
                && pending.corner() != Stageable.Corner.NORTHWEST;
        return follower ? null : new FellingHolder(pending);
    }

    @Override
    public boolean tickElementHolder(ServerLevel level, BlockPos pos, BlockState initialState) {
        return true;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
            boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        validate(level, pos, state);
        if (level.getBlockState(pos).getBlock() == this) {
            checkIdle(level, pos, level.getBlockState(pos));
        }
    }

    // A felling log nobody has worked on for a while, and has cost nobody anything, is a plain log
    // again. A log that was charged for stays staged, so an abandoned chop is not charged twice.
    private void checkIdle(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(level.getBlockEntity(pos) instanceof FellingLogBlockEntity entity)
                || entity.original() == null) {
            return;
        }
        BlockPos master = pos;
        FellingLogBlockEntity owner = entity;
        Stageable.Corner corner = entity.family() == Family.GROUP ? entity.corner() : null;
        if (corner != null) {
            master = Stageable.groupCells(pos, corner).get(0);
            if (level.getBlockEntity(master) instanceof FellingLogBlockEntity found) {
                owner = found;
            }
        }
        long now = level.getServer().getTickCount();
        if (owner.lastHit() > now) {
            owner.setLastHit(now);
        }
        int idle = ChoptSettings.IDLE_REVERT_TICKS.get(level.getServer());
        long quiet = now - owner.lastHit();
        if (quiet >= idle && !owner.worn()) {
            revert(level, pos, entity.original());
        } else {
            long wait = Math.max(20L, Math.min(idle - quiet, IDLE_RECHECK));
            level.scheduleTick(pos, this, (int) wait);
        }
    }

    // When a felling log goes, the cells beside it check themselves at the end of the tick.
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos near = pos.offset(dx, 0, dz);
                if ((dx != 0 || dz != 0) && level.getBlockState(near).getBlock() == this) {
                    RECHECK.add(new Awaiting(level, near, 0));
                }
            }
        }
    }

    /**
     * Puts the plain log back when this felling log cannot go on: it holds no data, or it belongs
     * to a group that is no longer whole.
     */
    public static void validate(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(level.getBlockEntity(pos) instanceof FellingLogBlockEntity entity)
                || entity.original() == null || entity.family() == null) {
            BlockState oak = Blocks.OAK_LOG.defaultBlockState();
            revert(level, pos, oak.setValue(AXIS, state.getValue(AXIS)));
        } else if (entity.family() == Family.GROUP && !groupIntact(level, pos, entity)) {
            revert(level, pos, entity.original());
        }
    }

    /**
     * Moves the log at {@code master} on to its next stage, and counts again the logs it brings
     * down, which a branch broken since may have changed.
     */
    public static void advance(ServerLevel level, BlockPos master, FellingLogBlockEntity entity) {
        BlockState state = level.getBlockState(master);
        int stage = entity.stageAt(state.getValue(PHASE));
        level.setBlock(master, state.setValue(PHASE, entity.phaseAt(stage + 1)),
                Block.UPDATE_CLIENTS);
        if (entity.family() != null && entity.original() != null) {
            entity.setLogsAbove(Region.ofStaged(level, master, entity.family()).n());
        }
        if (entity.stripped() != null) {
            Feedback.broke(level, master, entity.stripped(), null);
        }
        entity.save();
    }

    private static void revert(ServerLevel level, BlockPos pos, BlockState plain) {
        level.setBlock(pos, plain, Block.UPDATE_ALL);
    }

    // Whether all four cells of the group this cell belongs to are felling logs of that group. A
    // cell in an unloaded chunk cannot be judged, so it does not count against it.
    private static boolean groupIntact(ServerLevel level, BlockPos pos,
            FellingLogBlockEntity entity) {
        Stageable.Corner corner = entity.corner();
        if (corner == null) {
            return false;
        }
        List<BlockPos> cells = Stageable.groupCells(pos, corner);
        Stageable.Corner[] corners = Stageable.Corner.values();
        for (int i = 0; i < 4; i++) {
            BlockPos cell = cells.get(i);
            if (!level.hasChunkAt(cell)) {
                continue;
            }
            if (!(level.getBlockState(cell).getBlock() instanceof FellingLogBlock)
                    || !(level.getBlockEntity(cell) instanceof FellingLogBlockEntity other)
                    || other.family() != Family.GROUP || other.original() == null
                    || other.corner() != corners[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return state.getValue(WATERLOGGED) ? BARRIER_WET : BARRIER;
    }

    /**
     * Polymer's server-side mining runs for everyone but a chopping player, whose hold the poll
     * advances instead. Vanilla's own mining loop never completes a break of a block the client
     * sees as a barrier, so without this a plain break would be impossible.
     */
    @Override
    public boolean handleMiningOnServer(ItemStack tool, BlockState state, BlockPos pos,
            ServerPlayer player) {
        return !ChopTool.chopsAt(player, player.level(), pos);
    }

    /** Light gets through, as it does a barrier; no override on 26.2's Polymer, which lacks it. */
    public boolean forceLightInsideBlock(BlockState state) {
        return true;
    }

    /**
     * Exactly 0.0 for a chopping player, so nothing in vanilla or Polymer can complete their hold;
     * the original log's own progress for anyone else.
     */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level,
            BlockPos pos) {
        if (player instanceof ServerPlayer serverPlayer
                && ChopTool.chopsAt(serverPlayer, level, pos)) {
            return 0.0F;
        }
        return originalAt(level, pos).getDestroyProgress(player, level, pos);
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        BlockState original = entity instanceof FellingLogBlockEntity felling
                ? felling.originalOrOak() : Blocks.OAK_LOG.defaultBlockState();
        return original.getDrops(params);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state,
            boolean includeData) {
        return new ItemStack(originalAt(level, pos).getBlock());
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    // The log this was, or an oak log where the block entity is missing.
    private static BlockState originalAt(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FellingLogBlockEntity entity
                ? entity.originalOrOak() : Blocks.OAK_LOG.defaultBlockState();
    }
}
