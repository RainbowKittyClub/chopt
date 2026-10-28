package club.rainbowkitty.chopt.tree;

import java.util.ArrayList;
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
     * Where a log sits in a trunk of several: its column and row, counted from the northwest cell.
     * A trunk's cells are listed row by row from the northwest, so a cell's index is
     * {@code row * size + col}.
     */
    public record Cell(int col, int row) {
        /** The cell at {@code index} of a trunk that is {@code size} logs across. */
        public static Cell of(int index, int size) {
            return new Cell(index % size, index / size);
        }

        /** Whether this is the northwest cell, which holds the trunk's state. */
        public boolean master() {
            return col == 0 && row == 0;
        }
    }

    /** The corners of a 2x2 trunk, as a quarter log's {@code bark_side} names them. */
    public enum Corner {
        NORTHWEST(0, 0), NORTHEAST(1, 0), SOUTHEAST(1, 1), SOUTHWEST(0, 1);

        private final Cell cell;

        Corner(int col, int row) {
            this.cell = new Cell(col, row);
        }

        /** Where the corner is in a 2x2 trunk. */
        public Cell cell() {
            return cell;
        }

        /** The corner at {@code cell} of a 2x2 trunk, or null for any other cell. */
        public static @Nullable Corner at(Cell cell) {
            for (Corner corner : values()) {
                if (corner.cell.equals(cell)) {
                    return corner;
                }
            }
            return null;
        }

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
     * log, kept out by the tag, already stripped, a leafy small log,
     * or a quarter log whose 2x2 group is not whole. An upright plain log that stands in a whole
     * 2x2 or 3x3 group is part of it. Reads neighbouring chunks only where they are loaded.
     */
    public static @Nullable Family family(Level level, BlockPos pos, BlockState state) {
        if (!state.is(BlockTags.LOGS) || state.is(NEVER_STAGED)) {
            return null;
        }
        if (barkSide(state) != null) {
            Group quarter = group(level, pos, state);
            return quarter != null ? quarter.family() : null;
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
        Group trunk = group(level, pos, state);
        return trunk != null ? trunk.family() : Family.PILLAR;
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
     * The cells of the trunk, {@code size} logs across, that the cell at {@code pos}, which sits at
     * {@code cell} of it, belongs to: row by row from the northwest.
     */
    public static List<BlockPos> groupCells(BlockPos pos, int size, Cell cell) {
        BlockPos northwest = pos.offset(-cell.col(), 0, -cell.row());
        List<BlockPos> cells = new ArrayList<>(size * size);
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                cells.add(northwest.offset(col, 0, row));
            }
        }
        return List.copyOf(cells);
    }

    /**
     * A whole trunk of several logs.
     *
     * @param cells its cells, row by row from the northwest
     * @param family {@link Family#GROUP} for 2x2, {@link Family#GROUP3} for 3x3
     * @param cell where the queried cell sits in it
     */
    public record Group(List<BlockPos> cells, Family family, Cell cell) {}

    /**
     * The trunk the upright log {@code state} at {@code pos} belongs to, or null when there is
     * none whole: a cell unloaded, broken, not upright, placed rather than natural, or stripping
     * differently. A quarter log's corner is the one its bark faces, in a 2x2 trunk. A plain log
     * takes itself as each cell of a square in turn, a 3x3 one first and then a 2x2, whose cells
     * are all the same block, and joins the whole square with the fewest upright logs touching its
     * sides, the first of them on a tie, so two trunks that touch are each their own group
     * whichever cell is cut.
     */
    public static @Nullable Group group(Level level, BlockPos pos, BlockState state) {
        if (!isVertical(state)) {
            return null;
        }
        Corner bark = barkSide(state);
        if (bark != null) {
            return squareAt(level, pos, 2, bark.cell(), state.getBlock(), true);
        }
        if (!(state.getBlock() instanceof RotatedPillarBlock)) {
            return null;
        }
        for (int size = 3; size >= 2; size--) {
            Group best = null;
            int fewest = Integer.MAX_VALUE;
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    Group found = squareAt(level, pos, size, new Cell(col, row), state.getBlock(),
                            false);
                    int touching = found == null ? fewest : touching(level, found.cells());
                    if (touching < fewest) {
                        best = found;
                        fewest = touching;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
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

    // The square, {@code size} logs across, that has {@code pos} at {@code cell}, if all its cells
    // are natural, upright logs that strip to one block: quarter logs each facing its own corner,
    // or plain logs that are all {@code block}.
    private static @Nullable Group squareAt(Level level, BlockPos pos, int size, Cell cell,
            Block block, boolean quarter) {
        List<BlockPos> cells = groupCells(pos, size, cell);
        Block stripped = null;
        for (int i = 0; i < cells.size(); i++) {
            BlockPos at = cells.get(i);
            if (!level.hasChunkAt(at)) {
                return null;
            }
            BlockState cellState = level.getBlockState(at);
            boolean shape = quarter ? barkSide(cellState) == Corner.at(Cell.of(i, size))
                    : cellState.getBlock() == block;
            if (!cellState.is(BlockTags.LOGS) || cellState.is(NEVER_STAGED) || !shape
                    || !isVertical(cellState) || !Provenance.isNatural(level, at)) {
                return null;
            }
            Block target = Strip.of(level, at, cellState).getBlock();
            if (target == cellState.getBlock() || stripped != null && target != stripped) {
                return null;
            }
            stripped = target;
        }
        return new Group(cells, size == 3 ? Family.GROUP3 : Family.GROUP, cell);
    }
}
