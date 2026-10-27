package club.rainbowkitty.chopt.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.FakePlayer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.chopt.ledger.PlacedLogs;
import club.rainbowkitty.kalic0re.common.positiondata.Presence;

/**
 * {@code /chopt probe ledger}: whether a log is in the placed-logs ledger, and two region tools to
 * time the block-change hook. Bare, it reads the block the player looks at; {@code <pos>} reads
 * one position. {@code place <pos> [fake]} places an oak log as no player, or as a fake one.
 * {@code fill <from> <to> [marked]} sets oak logs, marking them when asked, and
 * {@code clear <from> <to>} sets air; each says how long it took.
 */
final class LedgerProbe {
    private LedgerProbe() {}

    // The literal to hang under /chopt probe.
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("ledger")
                .executes(LedgerProbe::atCrosshair)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(LedgerProbe::at))
                .then(Commands.literal("place")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> place(context, false))
                                .then(Commands.literal("fake")
                                        .executes(context -> place(context, true)))))
                .then(Commands.literal("fill")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> fill(context, false))
                                        .then(Commands.literal("marked")
                                                .executes(context -> fill(context, true))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(LedgerProbe::clear))));
    }

    // The ledger at the block the player looks at.
    private static int atCrosshair(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        BlockPos pos = Aim.block(context.getSource());
        return pos == null ? 0 : report(context.getSource(), pos);
    }

    private static int at(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return report(context.getSource(), BlockPosArgument.getBlockPos(context, "pos"));
    }

    // One line: the block, and marked, unmarked or unknown. Never loads a chunk.
    private static int report(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        Presence presence = PlacedLogs.marks().presence(level, pos);
        String block = presence == Presence.UNKNOWN ? "?"
                : BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        String answer = switch (presence) {
            case PRESENT -> "marked (placed)";
            case ABSENT -> "unmarked";
            case UNKNOWN -> "unknown";
        };
        source.sendSuccess(() -> Component.literal("ledger at " + pos.toShortString() + " ("
                + block + "): " + answer), false);
        return Command.SINGLE_SUCCESS;
    }

    // An oak log placed through BlockItem.place by no player, or by a fake one.
    private static int place(CommandContext<CommandSourceStack> context, boolean fake)
            throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        Player placer = fake ? FakePlayer.get(level) : null;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, 0.5, 0),
                Direction.UP, pos.below(), false);
        InteractionResult result = ((BlockItem) Items.OAK_LOG).place(new BlockPlaceContext(level,
                placer, InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG), hit));
        context.getSource().sendSuccess(() -> Component.literal("place at " + pos.toShortString()
                + (fake ? " by a fake player: " : " by nobody: ") + result), false);
        return result.consumesAction() ? Command.SINGLE_SUCCESS : 0;
    }

    // Oak logs over the box, marked or not, with the time it took.
    private static int fill(CommandContext<CommandSourceStack> context, boolean marked)
            throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        long start = System.nanoTime();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPosArgument.getBlockPos(context, "from"),
                BlockPosArgument.getBlockPos(context, "to"))) {
            level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 2);
            if (marked) {
                PlacedLogs.marks().mark(level, pos);
            }
            count++;
        }
        return timed(context.getSource(), marked ? "filled and marked" : "filled", count, start);
    }

    // Air over the box, with the time it took.
    private static int clear(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        long start = System.nanoTime();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPosArgument.getBlockPos(context, "from"),
                BlockPosArgument.getBlockPos(context, "to"))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            count++;
        }
        return timed(context.getSource(), "cleared", count, start);
    }

    private static int timed(CommandSourceStack source, String what, int count, long startNanos) {
        double millis = (System.nanoTime() - startNanos) / 1_000_000.0;
        source.sendSuccess(() -> Component.literal(String.format("%s %d blocks in %.2f ms", what,
                count, millis)), false);
        return count;
    }
}
