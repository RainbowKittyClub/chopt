package club.rainbowkitty.chopt.block;

import java.util.Set;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import club.rainbowkitty.chopt.Chopt;

/**
 * chopt's blocks. Both entries are Polymer server entries, so Fabric's registry sync skips them
 * and a client without the mod joins.
 */
public final class ChoptBlocks {
    /** A log being felled, in a given phase. */
    public static final FellingLogBlock FELLING_LOG = registerFellingLog();

    /** The block entity of {@link #FELLING_LOG}. */
    public static final BlockEntityType<FellingLogBlockEntity> FELLING_LOG_ENTITY =
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Chopt.id("felling_log"),
                    new BlockEntityType<>(FellingLogBlockEntity::new, Set.of(FELLING_LOG)));

    static {
        PolymerBlockUtils.registerBlockEntity(FELLING_LOG_ENTITY);
    }

    private ChoptBlocks() {}

    /** Forces registration; the fields above do the work. */
    public static void init() {
        FellingLogBlock.register();
    }

    // An oak log's strength, sound and fire behaviour, with the outline of a barrier: it must not
    // occlude, conduct redstone or suffocate, while behaving as a log to notes and fire.
    private static FellingLogBlock registerFellingLog() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Chopt.id("felling_log"));
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                .noOcclusion()
                .isRedstoneConductor((state, level, pos) -> false)
                .setId(key);
        return Registry.register(BuiltInRegistries.BLOCK, key, new FellingLogBlock(properties));
    }
}
