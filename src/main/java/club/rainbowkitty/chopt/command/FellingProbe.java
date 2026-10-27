package club.rainbowkitty.chopt.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;

/**
 * {@code /chopt probe felling <pos> <block state> <phase>}: makes the log a felling log in the
 * given phase, as the conversion will, for testing the block alone. A small-log state is a small
 * family, anything else a pillar; a quarter group needs its four cells and is not made here.
 */
final class FellingProbe {
    private FellingProbe() {}

    // The literal to hang under /chopt probe.
    static LiteralArgumentBuilder<CommandSourceStack> node(CommandBuildContext build) {
        return Commands.literal("felling")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("state", BlockStateArgument.block(build))
                                .then(Commands.argument("phase", IntegerArgumentType.integer(1, 15))
                                        .executes(FellingProbe::stage))));
    }

    private static int stage(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        BlockState original = BlockStateArgument.getBlock(context, "state").getState();
        Family family = Stageable.isSmall(original) ? Family.SMALL : Family.PILLAR;
        int phase = Math.min(IntegerArgumentType.getInteger(context, "phase"), family.phases());
        // The strip rules read the block at the position, so the log has to be there first.
        level.setBlock(pos, original, Block.UPDATE_ALL);
        FellingLogBlock.stage(level, pos, original, family, family.phases(), 0);
        BlockState felling = level.getBlockState(pos);
        level.setBlock(pos, felling.setValue(FellingLogBlock.PHASE, phase), Block.UPDATE_ALL);
        context.getSource().sendSuccess(() -> Component.literal("felling log at "
                + pos.toShortString() + ", " + family + " phase " + phase + " of "
                + family.phases()), false);
        return Command.SINGLE_SUCCESS;
    }
}
