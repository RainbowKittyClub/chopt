package club.rainbowkitty.chopt.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import club.rainbowkitty.chopt.block.FellingLogBlockEntity;

/**
 * {@code /chopt probe progress <pos>}: what each player has done on the current phase of the
 * felling log at a position, and the crack stage that shows. The work is kept in memory only, so
 * block data cannot say; a group's is on its northwest cell.
 */
final class ProgressProbe {
    private ProgressProbe() {}

    // The literal to hang under /chopt probe.
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("progress")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ProgressProbe::show));
    }

    private static int show(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        String line = context.getSource().getLevel().getBlockEntity(pos)
                instanceof FellingLogBlockEntity log
                ? "progress at " + pos.toShortString() + ": " + log.progressValues()
                        + " crack " + log.crackStage()
                : "no felling log at " + pos.toShortString();
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }
}
