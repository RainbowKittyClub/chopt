package club.rainbowkitty.chopt.tree;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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

    /** The corners of a 2x2 trunk, as a quarter log's {@code bark_side} names them. */
    public enum Corner {
        NORTHWEST, NORTHEAST, SOUTHEAST, SOUTHWEST;

        // The corner a serialized bark side names, or null.
        static @Nullable Corner named(String name) {
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
     * group is not whole. Reads neighbouring chunks only where they are loaded.
     */
    public static @Nullable Family family(Level level, BlockPos pos, BlockState state) {
        if (!state.is(BlockTags.LOGS) || state.is(NEVER_STAGED)) {
            return null;
        }
        if (barkSide(state) != null) {
            return quarterGroup(level, pos, state) != null ? Family.QUARTER : null;
        }
        if (!Strip.strips(level, pos, state)) {
            return null;
        }
        if (isSmall(state)) {
            return isLeafy(state) ? null : Family.SMALL;
        }
        boolean fullCube = Block.isShapeFullBlock(state.getShape(level, pos));
        return state.getBlock() instanceof RotatedPillarBlock && fullCube ? Family.PILLAR : null;
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
     * The four cells of the 2x2 trunk the quarter log at {@code pos} belongs to, northwest first
     * and then clockwise, or null when the group is not whole: a cell unloaded, broken, not
     * upright, facing the wrong corner, placed rather than natural, or stripping differently.
     */
    public static @Nullable List<BlockPos> quarterGroup(Level level, BlockPos pos,
            BlockState state) {
        Corner corner = barkSide(state);
        if (corner == null || !isVertical(state)) {
            return null;
        }
        int east = corner == Corner.NORTHEAST || corner == Corner.SOUTHEAST ? 1 : 0;
        int south = corner == Corner.SOUTHEAST || corner == Corner.SOUTHWEST ? 1 : 0;
        BlockPos northwest = pos.offset(-east, 0, -south);
        List<BlockPos> cells = List.of(northwest, northwest.east(), northwest.east().south(),
                northwest.south());
        Corner[] expected = Corner.values();
        Block stripped = null;
        for (int i = 0; i < 4; i++) {
            BlockPos cell = cells.get(i);
            if (!level.hasChunkAt(cell)) {
                return null;
            }
            BlockState cellState = level.getBlockState(cell);
            if (!cellState.is(BlockTags.LOGS) || cellState.is(NEVER_STAGED)
                    || barkSide(cellState) != expected[i] || !isVertical(cellState)
                    || !Provenance.isNatural(level, cell)) {
                return null;
            }
            Block target = Strip.of(level, cell, cellState).getBlock();
            if (target == cellState.getBlock() || stripped != null && target != stripped) {
                return null;
            }
            stripped = target;
        }
        return cells;
    }
}
