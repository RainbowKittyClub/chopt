package club.rainbowkitty.chopt.tree;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

import club.rainbowkitty.chopt.Chopt;
import club.rainbowkitty.chopt.ledger.Provenance;

/**
 * Which shape of log a block is, if it is one chopt stages at all. Everything else breaks as in
 * vanilla. Shapes are recognised by their properties and by their model of the world, not by
 * class, so a modded log of the same shape is staged without code.
 */
public final class Stageable {
    /** Logs a pack keeps out of the felling, such as a shrub log that changes size with leaves. */
    public static final TagKey<Block> NEVER_STAGED =
            TagKey.create(Registries.BLOCK, Chopt.id("never_staged"));

    private static final List<String> SMALL_PROPERTIES = List.of(
            "axis", "up", "down", "north", "east", "south", "west", "waterlogged");

    private Stageable() {}

    /**
     * The corners of a 2x2 trunk, as a quarter log's {@code bark_side} names them. In this order
     * they go clockwise from the northwest, which is the order of a group's cells.
     */
    public enum Corner {
        NORTHWEST, NORTHEAST, SOUTHEAST, SOUTHWEST;

        /** The corner a serialized bark side or saved name stands for, or null. */
        public static @Nullable Corner named(String name) {
            for (Corner corner : values()) {
                if (corner.name().equalsIgnoreCase(name)) {
                    return corner;
                }
            }
            return null;
        }
    }

    /**
     * The family of the log {@code state} at {@code pos}, or null when it is not staged: not a
     * log, kept out by the tag, already stripped, a leafy small log, or a quarter log whose 2x2
     * group is not whole. An upright plain log that stands in a whole 2x2 group is part of it.
     * Reads neighbouring chunks only where they are loaded.
     */
    public static @Nullable Family family(Level level, BlockPos pos, BlockState state) {
        if (!state.is(BlockTags.LOGS) || state.is(NEVER_STAGED)) {
            return null;
        }
        if (barkSide(state) != null) {
            return group(level, pos, state) != null ? Family.GROUP : null;
        }
        if (!Strip.strips(level, pos, state)) {
            return null;
        }
        if (isSmall(state)) {
            return isLeafy(state) ? null : Family.SMALL;
        }
        boolean fullCube = Block.isShapeFullBlock(state.getShape(level, pos));
        if (!(state.getBlock() instanceof RotatedPillarBlock) || !fullCube) {
            return null;
        }
        return group(level, pos, state) != null ? Family.GROUP : Family.PILLAR;
    }

    /** Whether {@code state} is a small log: the Terraform shape, with arms on every side. */
    public static boolean isSmall(BlockState state) {
        StateDefinition<Block, BlockState> definition = state.getBlock().getStateDefinition();
        for (String name : SMALL_PROPERTIES) {
            if (definition.getProperty(name) == null) {
                return false;
            }
        }
        return true;
    }

    /** Whether a small log carries leaves, which its break drops first. */
    public static boolean isLeafy(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("has_leaves");
        return property != null && Boolean.TRUE.equals(state.getValue(property));
    }

    /** {@code state} without its leaves, a leafy small log's bare log. */
    public static BlockState withoutLeaves(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("has_leaves");
        if (property instanceof BooleanProperty leaves) {
            return state.setValue(leaves, false);
        }
        return state;
    }

    /**
     * The leaves block a leafy small log at {@code pos} carries, asked through its
     * {@code leavesAt(BlockGetter, BlockPos)}; null for a block that does not answer.
     */
    public static @Nullable Block leavesOf(BlockGetter level, BlockPos pos, BlockState state) {
        try {
            Object leaves = state.getBlock().getClass()
                    .getMethod("leavesAt", BlockGetter.class, BlockPos.class)
                    .invoke(state.getBlock(), level, pos);
            return leaves instanceof Block block ? block : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Whether {@code state} stands upright. */
    public static boolean isVertical(BlockState state) {
        return state.hasProperty(BlockStateProperties.AXIS)
                && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y;
    }

    /** The corner of its 2x2 trunk a quarter log's bark faces, or null for any other block. */
    public static @Nullable Corner barkSide(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("bark_side");
        if (property == null || !(state.getValue(property) instanceof StringRepresentable side)) {
            return null;
        }
        return Corner.named(side.getSerializedName());
    }

    /**
     * The four cells of the 2x2 trunk that the cell at {@code pos}, which sits at {@code corner} of
     * it, belongs to: northwest first and then clockwise.
     */
    public static List<BlockPos> groupCells(BlockPos pos, Corner corner) {
        int east = corner == Corner.NORTHEAST || corner == Corner.SOUTHEAST ? 1 : 0;
        int south = corner == Corner.SOUTHEAST || corner == Corner.SOUTHWEST ? 1 : 0;
        BlockPos northwest = pos.offset(-east, 0, -south);
        return List.of(northwest, northwest.east(), northwest.east().south(), northwest.south());
    }

    /**
     * A whole 2x2 trunk.
     *
     * @param cells its four cells, northwest first and then clockwise
     * @param corner the corner the queried cell sits at
     */
    public record Group(List<BlockPos> cells, Corner corner) {}

    /**
     * The 2x2 trunk the upright log {@code state} at {@code pos} belongs to, or null when there is
     * none whole: a cell unloaded, broken, not upright, placed rather than natural, or stripping
     * differently. A quarter log's corner is the one its bark faces. A plain log takes itself as
     * northwest, northeast, southeast and then southwest of a square whose four cells are all the
     * same block, and joins the whole square with the fewest upright logs touching its sides, the
     * first of them on a tie, so two trunks that touch are each their own group whichever cell is
     * cut.
     */
    public static @Nullable Group group(Level level, BlockPos pos, BlockState state) {
        if (!isVertical(state)) {
            return null;
        }
        Corner bark = barkSide(state);
        if (bark != null) {
            return squareAt(level, pos, bark, state.getBlock(), true);
        }
        if (!(state.getBlock() instanceof RotatedPillarBlock)) {
            return null;
        }
        Group best = null;
        int fewest = Integer.MAX_VALUE;
        for (Corner corner : Corner.values()) {
            Group found = squareAt(level, pos, corner, state.getBlock(), false);
            int touching = found == null ? fewest : touching(level, found.cells());
            if (touching < fewest) {
                best = found;
                fewest = touching;
            }
        }
        return best;
    }

    // How many upright logs stand against the sides of a square, outside it.
    private static int touching(Level level, List<BlockPos> cells) {
        int count = 0;
        for (BlockPos cell : cells) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (!cells.contains(next) && level.hasChunkAt(next)) {
                    BlockState there = level.getBlockState(next);
                    if (there.is(BlockTags.LOGS) && isVertical(there)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // The square that has {@code pos} at {@code corner}, if all four of its cells are natural,
    // upright logs that strip to one block: quarter logs each facing its own corner, or plain logs
    // that are all {@code block}.
    private static @Nullable Group squareAt(Level level, BlockPos pos, Corner corner, Block block,
            boolean quarter) {
        List<BlockPos> cells = groupCells(pos, corner);
        Corner[] expected = Corner.values();
        Block stripped = null;
        for (int i = 0; i < 4; i++) {
            BlockPos cell = cells.get(i);
            if (!level.hasChunkAt(cell)) {
                return null;
            }
            BlockState cellState = level.getBlockState(cell);
            boolean shape = quarter ? barkSide(cellState) == expected[i]
                    : cellState.getBlock() == block;
            if (!cellState.is(BlockTags.LOGS) || cellState.is(NEVER_STAGED) || !shape
                    || !isVertical(cellState) || !Provenance.isNatural(level, cell)) {
                return null;
            }
            Block target = Strip.of(level, cell, cellState).getBlock();
            if (target == cellState.getBlock() || stripped != null && target != stripped) {
                return null;
            }
            stripped = target;
        }
        return new Group(cells, corner);
    }
}
