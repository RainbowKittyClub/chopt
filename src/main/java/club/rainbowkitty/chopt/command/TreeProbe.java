package club.rainbowkitty.chopt.command;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.ledger.Provenance;
import club.rainbowkitty.chopt.tree.Canopy;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Region;
import club.rainbowkitty.chopt.tree.Stageable;
import club.rainbowkitty.chopt.tree.Strip;
import club.rainbowkitty.chopt.tree.TimeCurve;

/**
 * {@code /chopt probe tree [pos]}: what the tree model makes of the log the player looks at, or at
 * a position: its family, whether it is natural, the region and {@code n}, the canopy verdict, the
 * stripped block and, for a player, the held tool's timing. {@code scan <from> <to>} counts the
 * logs of a box by shape and lists some horizontal ones.
 */
final class TreeProbe {
    private static final int LISTED = 4;

    private TreeProbe() {}

    // What the tree model says about one log.
    private record Verdict(BlockState state, Family family, boolean natural, Region region,
            boolean canopy) {
        boolean stageable() {
            return family != null && natural && region.stageable() && canopy;
        }
    }

    // The literal to hang under /chopt probe.
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("tree")
                .executes(TreeProbe::atCrosshair)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(TreeProbe::at))
                .then(Commands.literal("scan")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(TreeProbe::scan))));
    }

    private static int atCrosshair(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        BlockPos pos = Aim.block(context.getSource());
        return pos == null ? 0 : report(context.getSource(), pos);
    }

    private static int at(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return report(context.getSource(), BlockPosArgument.getBlockPos(context, "pos"));
    }

    // The verdict for the block at pos, in a loaded chunk.
    private static Verdict judge(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Family family = Stageable.family(level, pos, state);
        boolean natural = Provenance.isNatural(level, pos);
        Region region = family == null ? Region.none(Family.PILLAR)
                : Region.of(level, pos, state, family);
        boolean canopy = family != null && region.n() > 0 && Canopy.passes(level, region);
        return new Verdict(state, family, natural, region, canopy);
    }

    private static int report(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        if (!level.hasChunkAt(pos)) {
            say(source, "tree at " + pos.toShortString() + ": chunk not loaded");
            return Command.SINGLE_SUCCESS;
        }
        Verdict verdict = judge(level, pos);
        BlockState state = verdict.state();
        Family family = verdict.family();
        Region region = verdict.region();
        say(source, "tree at " + pos.toShortString() + " ("
                + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ")");
        say(source, "  family: " + (family == null ? "none (breaks as vanilla)"
                : family + ", K = " + family.phases()));
        say(source, "  natural: " + verdict.natural() + ", stripped: "
                + BuiltInRegistries.BLOCK.getKey(Strip.of(level, pos, state).getBlock()));
        say(source, "  region: " + region.members().size() + " logs, n = " + region.n()
                + ", cluster " + region.cluster().size()
                + (region.cluster().size() > 1 ? " from " + region.cluster().get(0).toShortString()
                        : ""));
        say(source, "  canopy: " + verdict.canopy() + ", stageable: " + verdict.stageable());
        if (family != null && source.getEntity() instanceof ServerPlayer player) {
            double base = state.getDestroyProgress(player, level, pos);
            double m = TimeCurve.m(level.getServer(), region.n());
            double ticks = base > 0 ? (m - 1.0) / base : 0.0;
            say(source, String.format("  tool %s: base %.4f, (m - 1) / base %.1f ticks, K_eff %d",
                    player.getMainHandItem().getItem(), base, ticks,
                    TimeCurve.effectivePhases(family, m, base)));
        }
        return Command.SINGLE_SUCCESS;
    }

    // Counts every loaded log in the box by shape, how many would be staged, and lists a few
    // horizontal ones with their n.
    private static int scan(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Map<Family, Integer> shapes = new EnumMap<>(Family.class);
        int upright = 0;
        int horizontal = 0;
        int other = 0;
        int staged = 0;
        List<String> listed = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPosArgument.getBlockPos(context, "from"),
                BlockPosArgument.getBlockPos(context, "to"))) {
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
                continue;
            }
            Verdict verdict = judge(level, pos);
            if (verdict.family() == null) {
                other++;
                continue;
            }
            shapes.merge(verdict.family(), 1, Integer::sum);
            boolean flat = !Stageable.isVertical(verdict.state());
            if (flat) {
                horizontal++;
            } else {
                upright++;
            }
            if (verdict.stageable()) {
                staged++;
                if (flat && listed.size() < LISTED) {
                    listed.add(pos.toShortString() + " n=" + verdict.region().n());
                }
            }
        }
        say(source, "scan: " + shapes + ", upright " + upright + ", horizontal " + horizontal
                + ", not staged " + other + "; stageable " + staged);
        listed.forEach(line -> say(source, "  horizontal stageable: " + line));
        return Command.SINGLE_SUCCESS;
    }

    private static void say(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
