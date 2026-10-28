package club.rainbowkitty.chopt.chop;

import java.util.List;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.display.Feedback;
import club.rainbowkitty.chopt.tree.Region;
import club.rainbowkitty.chopt.tree.Stageable;

/**
 * Brings a tree down when its last phase completes: settles what the tool still owes, then has
 * {@link Falls} remove the cut log and every log that hangs from it, lowest first. Each log asks
 * the break event first, so a claim can refuse it, and a log whose tool could not pay for it stays.
 */
public final class Feller {
    // What vanilla charges in hunger for each block a player breaks.
    private static final float EXHAUSTION = 0.005F;

    private Feller() {}

    /**
     * The last phase of the log at {@code master} has been completed by {@code finisher}. The
     * phases before it have each been paid by whoever completed them, the finisher pays the rest,
     * and the logs fall as far as the durability paid for them goes.
     */
    public static void fell(ServerLevel level, BlockPos master, ServerPlayer finisher) {
        if (!(level.getBlockEntity(master) instanceof FellingLogBlockEntity log)
                || log.original() == null || log.family() == null) {
            return;
        }
        Region region = Region.ofStaged(level, master, log.family());
        List<BlockPos> cluster = region.cluster().isEmpty() ? List.of(master) : region.cluster();
        List<BlockPos> members = region.members().isEmpty() ? cluster : region.members();
        ItemStack tool = finisher.getMainHandItem();
        Item toolItem = tool.getItem();
        // As vanilla counts a use: only an item with a tool component, even one this wears out.
        boolean tooled = tool.get(DataComponents.TOOL) != null;

        // Counted again now, so what is owed is judged against the tree as it stands.
        log.setLogsAbove(members.size() - 1);
        int nested = nestedPaid(level, members, cluster);
        int top = Math.max(0, members.size() - log.paid() - nested);
        log.setPaid(log.paid() + Durability.charge(finisher, tool, top));
        int allowed = Math.max(cluster.size(), Math.min(members.size(), log.paid() + nested));

        Falls.start(level, finisher, tool, cluster, members, allowed);
        if (tooled) {
            finisher.awardStat(Stats.ITEM_USED.get(toolItem));
        }
    }

    // What the other felling logs in the region have already been charged for.
    private static int nestedPaid(ServerLevel level, List<BlockPos> members,
            List<BlockPos> cluster) {
        int paid = 0;
        for (BlockPos pos : members) {
            if (cluster.contains(pos)) {
                continue;
            }
            if (level.getBlockEntity(pos) instanceof FellingLogBlockEntity other) {
                paid += other.paid();
            }
        }
        return paid;
    }

    // Removes one log as a break would: the event may refuse it, then the break effect, its drops,
    // the block, the fluid it held, the event again and the statistics. The effect is the sound and
    // chips of a break, or only the chips when {@code sound} is false. Returns whether it fell.
    static boolean remove(ServerLevel level, ServerPlayer breaker, ItemStack tool, BlockPos pos,
            boolean sound) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LOGS)) {
            return false;
        }
        if (Stageable.isLeafy(state)) {
            state = stripLeaves(level, breaker, tool, pos, state);
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, breaker, pos, state,
                entity)) {
            return false;
        }
        Block mined = state.getBlock();
        BlockState shown = state;
        if (entity instanceof FellingLogBlockEntity log) {
            mined = log.originalOrOak().getBlock();
            shown = log.stripped() != null ? log.stripped() : log.originalOrOak();
        }
        if (sound) {
            Feedback.broke(level, pos, shown, null);
        } else {
            Feedback.chips(level, pos, shown, Feedback.FALL_CHIPS);
        }
        Block.dropResources(state, level, pos, entity, breaker, tool);
        level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, breaker, pos, state, entity);
        breaker.awardStat(Stats.BLOCK_MINED.get(mined));
        breaker.causeFoodExhaustion(EXHAUSTION);
        return true;
    }

    // A leafy small log gives up its leaves first, as breaking just the leaves would, so the
    // listeners that take the leaves on the break event find a bare log. The leaves are free: they
    // are part of the log, which is what the tool pays for.
    private static BlockState stripLeaves(ServerLevel level, ServerPlayer breaker, ItemStack tool,
            BlockPos pos, BlockState state) {
        Block leaves = Stageable.leavesOf(level, pos, state);
        if (leaves != null) {
            Block.dropResources(leaves.defaultBlockState(), level, pos, null, breaker, tool);
            breaker.awardStat(Stats.BLOCK_MINED.get(leaves));
        }
        BlockState bare = Stageable.withoutLeaves(state);
        level.setBlock(pos, bare, Block.UPDATE_ALL);
        return bare;
    }

    // Puts a cut cell that did not fall back as the log it was, so it does not wait forever.
    static void restore(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).getBlock() instanceof FellingLogBlock
                && level.getBlockEntity(pos) instanceof FellingLogBlockEntity log) {
            level.setBlock(pos, log.originalOrOak(), Block.UPDATE_ALL);
        }
    }
}
