package club.rainbowkitty.chopt.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.chop.ChopTool;
import club.rainbowkitty.chopt.hold.ChopPoll;

/**
 * {@code /chopt probe hold <player>}: whether the player chops and, for the felling log they
 * aim at, how far they have come through its phase and what the tool has paid;
 * {@code ask <player> <pos>} says what the attack-block event answers for them there, which is the
 * question a claim mod is asked when a chop begins.
 */
final class HoldProbe {
    private HoldProbe() {}

    // The literal to hang under /chopt probe.
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("hold")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(HoldProbe::show)
                        .then(Commands.literal("ask")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(HoldProbe::ask))));
    }

    private static int show(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        String ray = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().toShortString() + " "
                + BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(hit.getBlockPos())
                        .getBlock()) : "nothing";
        String line = "chops " + ChopTool.chops(player) + ", swinging "
                + swinging(player) + ", ray hits " + ray + ", log " + progress(player, hit);
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }

    // The player's own progress on the felling log the ray hits, or none.
    private static String progress(ServerPlayer player, BlockHitResult hit) {
        if (hit.getType() != HitResult.Type.BLOCK
                || !(player.level().getBlockState(hit.getBlockPos()).getBlock()
                        instanceof FellingLogBlock)) {
            return "none";
        }
        BlockPos master = ChopPoll.masterPosOf(player.level(), hit.getBlockPos());
        if (master == null || !(player.level().getBlockEntity(master)
                instanceof FellingLogBlockEntity log)) {
            return "none";
        }
        int phase = player.level().getBlockState(master).getValue(FellingLogBlock.PHASE);
        return "at " + master.toShortString() + " stage " + log.stageAt(phase) + " of "
                + log.phases() + ", progress " + log.progressOf(player.getUUID()) + ", paid "
                + log.paid() + " of " + (log.logsAbove() + 1);
    }

    // Whether the player is mid-swing, which is what the poll counts as chopping.
    private static boolean swinging(ServerPlayer player) {
        //? if >=26.3 {
        return player.isSwinging();
        //?} else {
        /*return player.swinging;
        *///?}
    }

    private static int ask(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        InteractionResult result = AttackBlockCallback.EVENT.invoker().interact(player,
                player.level(), InteractionHand.MAIN_HAND, pos, Direction.UP);
        context.getSource().sendSuccess(() -> Component.literal("attack-block at "
                + pos.toShortString() + " answers " + result), false);
        return Command.SINGLE_SUCCESS;
    }
}
