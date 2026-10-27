package club.rainbowkitty.chopt.block;

import java.util.ArrayList;
import java.util.List;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
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

import club.rainbowkitty.chopt.chop.ChopTool;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;
import club.rainbowkitty.chopt.tree.Strip;

/**
 * A log being felled. To every client it is a barrier, so its outline and collision never change
 * as the phases go by; the shrinking log is a display inside it (S7). The block entity holds the
 * log it was. A vanilla break of this block gives that log back, so a sneaking player, a bare
 * hand, a tool that cannot chop or another mod breaks it as the plain log it was.
 */
public class FellingLogBlock extends BarrierBlock implements PolymerBlock, EntityBlock {
    /** The current phase, from 1; the northwest cell of a quarter group holds the group's. */
    public static final IntegerProperty PHASE = IntegerProperty.create("phase", 1, 15);
    /** The axis the display runs along, which the log's own axis is for a plain log. */
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    private static final BlockState BARRIER = Blocks.BARRIER.defaultBlockState();
    private static final BlockState BARRIER_WET = BARRIER.setValue(WATERLOGGED, true);
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    /** The data a new felling log is built with, handed to its block entity while it is built. */
    record Pending(BlockPos pos, BlockState original, BlockState stripped, Family family,
            int phases, int logsAbove) {}

    public FellingLogBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(PHASE, 1)
                .setValue(AXIS, Direction.Axis.Y));
    }

    /** Checks every felling log a chunk loads, which a crash or an edit may have left unsound. */
    public static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof FellingLogBlockEntity) {
                level.scheduleTick(entity.getBlockPos(), ChoptBlocks.FELLING_LOG, 1);
            }
        });
    }

    /**
     * Turns the log at {@code pos} into a felling log in phase 1. The stripped look is worked out
     * first, while the log is still there to be read.
     */
    public static void stage(Level level, BlockPos pos, BlockState original, Family family,
            int phases, int logsAbove) {
        BlockState stripped = Strip.of(level, pos, original);
        BlockState felling = ChoptBlocks.FELLING_LOG.defaultBlockState()
                .setValue(AXIS, displayAxis(original, family))
                .setValue(WATERLOGGED, original.hasProperty(WATERLOGGED)
                        && original.getValue(WATERLOGGED));
        PENDING.set(new Pending(pos, original, stripped, family, phases, logsAbove));
        try {
            level.setBlock(pos, felling, Block.UPDATE_ALL);
        } finally {
            PENDING.remove();
        }
    }

    /**
     * Turns the four cells of a quarter group into felling logs, the northwest cell first so that
     * the others find their master when they check themselves. A failure puts back every cell
     * already turned.
     */
    public static void stageGroup(Level level, List<BlockPos> cells, int phases, int logsAbove) {
        List<BlockState> originals = new ArrayList<>();
        for (BlockPos cell : cells) {
            originals.add(level.getBlockState(cell));
        }
        int done = 0;
        try {
            for (; done < cells.size(); done++) {
                stage(level, cells.get(done), originals.get(done), Family.QUARTER, phases,
                        logsAbove);
            }
        } catch (RuntimeException e) {
            for (int i = 0; i < done; i++) {
                level.setBlock(cells.get(i), originals.get(i), Block.UPDATE_ALL);
            }
            throw e;
        }
    }

    // The axis a log's display runs along: its own for a plain log, the main axis of a small log
    // (up and down, else east and west, else north-south), and always upright for a quarter group.
    private static Direction.Axis displayAxis(BlockState original, Family family) {
        if (family == Family.QUARTER) {
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
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
            boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        validate(level, pos, state);
    }

    /**
     * Puts the plain log back when this felling log cannot go on: it holds no data, or it belongs
     * to a quarter group that is no longer whole.
     */
    public static void validate(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(level.getBlockEntity(pos) instanceof FellingLogBlockEntity entity)
                || entity.original() == null || entity.family() == null) {
            BlockState oak = Blocks.OAK_LOG.defaultBlockState();
            revert(level, pos, oak.setValue(AXIS, state.getValue(AXIS)));
        } else if (entity.family() == Family.QUARTER && !groupIntact(level, pos, entity)) {
            revert(level, pos, entity.original());
        }
    }

    private static void revert(ServerLevel level, BlockPos pos, BlockState plain) {
        level.setBlock(pos, plain, Block.UPDATE_ALL);
    }

    // Whether all four cells of the quarter group this cell belongs to are felling logs of that
    // group. A cell in an unloaded chunk cannot be judged, so it does not count against it.
    private static boolean groupIntact(ServerLevel level, BlockPos pos,
            FellingLogBlockEntity entity) {
        Stageable.Corner corner = Stageable.barkSide(entity.original());
        if (corner == null) {
            return false;
        }
        List<BlockPos> cells = Stageable.quarterCells(pos, corner);
        Stageable.Corner[] corners = Stageable.Corner.values();
        for (int i = 0; i < 4; i++) {
            BlockPos cell = cells.get(i);
            if (!level.hasChunkAt(cell)) {
                continue;
            }
            if (!(level.getBlockState(cell).getBlock() instanceof FellingLogBlock)
                    || !(level.getBlockEntity(cell) instanceof FellingLogBlockEntity other)
                    || other.family() != Family.QUARTER || other.original() == null
                    || Stageable.barkSide(other.original()) != corners[i]) {
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
        return !ChopTool.chops(player);
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
        if (player instanceof ServerPlayer serverPlayer && ChopTool.chops(serverPlayer)) {
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
