package club.rainbowkitty.chopt.tree;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import club.rainbowkitty.chopt.Chopt;
import club.rainbowkitty.chopt.ledger.Provenance;

/**
 * The second gate on "natural": a tree has a crown. The ledger rules out a log a player placed;
 * this rules out a stack of logs the ledger could not see, such as a structure's.
 */
public final class Canopy {
    /** Blocks that make a crown: leaves, nether wart blocks and shroomlight. */
    public static final TagKey<Block> CANOPY = TagKey.create(Registries.BLOCK, Chopt.id("canopy"));

    private static final int RADIUS = 2;

    private Canopy() {}

    /**
     * Whether some block within 2 of a region log is canopy. Leaves a player placed do not count,
     * and a natural leafy small log does. Reads loaded chunks only.
     */
    public static boolean passes(ServerLevel level, Region region) {
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos log : region.members()) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                    for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                        BlockPos pos = log.offset(dx, dy, dz);
                        if (seen.add(pos) && level.hasChunkAt(pos) && crowns(level, pos)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // Whether the block at pos, in a loaded chunk, is part of a crown.
    private static boolean crowns(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(CANOPY)) {
            return !state.hasProperty(BlockStateProperties.PERSISTENT)
                    || !state.getValue(BlockStateProperties.PERSISTENT);
        }
        return state.is(BlockTags.LOGS) && Stageable.isLeafy(state)
                && Provenance.isNatural(level, pos);
    }
}
