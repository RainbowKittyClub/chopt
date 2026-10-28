package club.rainbowkitty.chopt.chop;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.Chopt;
import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;

/**
 * A 2x2 group that has been started is not broken up by hand. A player in survival chops it even
 * while sneaking ({@link ChopTool#chopsAt}), with whatever they hold, at the pace of any chop. What
 * reaches here is a plain break of one of its cells by code that breaks it for a player, which
 * would drop that cell's log and leave the other three to revert, so it is refused. Creative
 * players still break a cell as plain, so a group can always be cleared. Runs after the default
 * phase, so a claim that refuses the break refuses it first.
 */
public final class GroupBreaks {
    private static final Identifier PHASE = Chopt.id("group_breaks");

    private GroupBreaks() {}

    /** Registers the handler after the default phase. */
    public static void register() {
        PlayerBlockBreakEvents.BEFORE.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        PlayerBlockBreakEvents.BEFORE.register(PHASE, GroupBreaks::beforeBreak);
    }

    private static boolean beforeBreak(Level world, Player player, BlockPos pos, BlockState state,
            BlockEntity blockEntity) {
        boolean refused = world instanceof ServerLevel && player instanceof ServerPlayer breaker
                && BreakScope.current() == breaker && !Cascade.active()
                && state.getBlock() instanceof FellingLogBlock
                && blockEntity instanceof FellingLogBlockEntity log && log.family().isGroup()
                && !breaker.gameMode.isCreative();
        return !refused;
    }
}
