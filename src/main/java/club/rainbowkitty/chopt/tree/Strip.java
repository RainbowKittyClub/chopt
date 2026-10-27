package club.rainbowkitty.chopt.tree;

//? if <26.3 {
/*import java.lang.reflect.Field;
import java.util.Map;
*///?}

import net.minecraft.core.BlockPos;
//? if >=26.3 {
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.component.BlockTransformers;
//?}
import net.minecraft.world.level.Level;
//? if <26.3 {
/*import net.minecraft.world.level.block.Block;
*///?}
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.3 {
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider;
//?}

/**
 * What an axe turns a log into. 26.2 reads vanilla's strippable table, which Fabric API's
 * registry also writes to; 26.3 evaluates the axe's block transformer, a datapack registry.
 */
public final class Strip {
    private Strip() {}

    /**
     * The stripped state of the log at {@code pos}, axis and other properties kept, or
     * {@code state} itself when an axe does nothing to it. Call it while the log is still in the
     * world: the 26.3 rules read the block at the position.
     */
    public static BlockState of(Level level, BlockPos pos, BlockState state) {
        //? if >=26.3 {
        BlockTransformer axe = level.registryAccess().lookupOrThrow(Registries.BLOCK_TRANSFORMER)
                .getValue(BlockTransformers.AXE);
        if (axe == null) {
            return state;
        }
        RandomSource random = RandomSource.create(0L);
        for (BlockTransformer.BlockTransformData data : axe.transforms()) {
            if (data.blockStateProvider().value() instanceof RuleBasedStateProvider rules) {
                BlockState stripped = rules.getOptionalState(level, random, pos);
                if (stripped != null && stripped.getBlock() != state.getBlock()) {
                    return stripped;
                }
            }
        }
        return state;
        //?} else {
        /*Block stripped = strippables().get(state.getBlock());
        return stripped == null ? state : stripped.withPropertiesOf(state);
        *///?}
    }

    /** Whether an axe turns the log at {@code pos} into a different block. */
    public static boolean strips(Level level, BlockPos pos, BlockState state) {
        return of(level, pos, state).getBlock() != state.getBlock();
    }

    //? if <26.3 {
    /*// Vanilla's table is protected and Fabric API replaces its contents, so read it afresh.
    @SuppressWarnings("unchecked")
    private static Map<Block, Block> strippables() {
        try {
            Field field = Class.forName("net.minecraft.world.item.AxeItem")
                    .getDeclaredField("STRIPPABLES");
            field.setAccessible(true);
            return (Map<Block, Block>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AxeItem.STRIPPABLES is gone", e);
        }
    }
    *///?}
}
