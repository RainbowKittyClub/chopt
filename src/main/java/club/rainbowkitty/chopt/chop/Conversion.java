package club.rainbowkitty.chopt.chop;

import java.util.List;

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
import club.rainbowkitty.chopt.display.Feedback;
import club.rainbowkitty.chopt.ledger.Provenance;
import club.rainbowkitty.chopt.tree.Canopy;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Region;
import club.rainbowkitty.chopt.tree.Stageable;
import club.rainbowkitty.chopt.tree.Strip;
import club.rainbowkitty.chopt.tree.TimeCurve;

/**
 * Turns the log a chopping player breaks into a felling log. It runs as a player block-break
 * handler after the default phase, so claim and protection mods that deny a break run first and a
 * denial means no conversion. Returning false cancels the vanilla break before it reaches the
 * tool, so the break costs no durability and awards no stat; Fabric re-sends the blocks around it,
 * which now show the felling log.
 */
public final class Conversion {
    private static final Identifier PHASE = Chopt.id("conversion");

    private Conversion() {}

    /** Registers the handler after the default phase. */
    public static void register() {
        PlayerBlockBreakEvents.BEFORE.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        PlayerBlockBreakEvents.BEFORE.register(PHASE, Conversion::beforeBreak);
    }

    private static boolean beforeBreak(Level world, Player player, BlockPos pos, BlockState state,
            BlockEntity blockEntity) {
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer breaker)
                || BreakScope.current() != breaker || Cascade.active()
                || !ChopTool.chops(breaker)) {
            return true;
        }
        Family family = Stageable.family(level, pos, state);
        if (family == null || !Provenance.isNatural(level, pos)) {
            return true;
        }
        Region region = Region.of(level, pos, state, family);
        if (!region.stageable() || region.n() < 1 || !Canopy.passes(level, region)) {
            return true;
        }
        if (family == Family.GROUP && !siblingsAgree(level, breaker, region, pos)) {
            return true;
        }
        // Read while the log is still there: the strip rules look at the block at the position.
        BlockState stripped = Strip.of(level, pos, state);
        double base = state.getDestroyProgress(breaker, level, pos);
        double m = TimeCurve.m(level.getServer(), region.n());
        int phases = TimeCurve.effectivePhases(family, m, base);
        if (family == Family.GROUP) {
            FellingLogBlock.stageGroup(level, region.cluster(), phases, region.n());
        } else {
            FellingLogBlock.stage(level, pos, state, family, null, phases, region.n());
        }
        Feedback.broke(level, pos, stripped, breaker);
        return false;
    }

    // Asks the break event for the three cells the cut does not break itself, inside a cascade so
    // that it does not convert them: a claim that covers any of them forbids the cut as a whole.
    private static boolean siblingsAgree(ServerLevel level, ServerPlayer breaker, Region region,
            BlockPos cut) {
        boolean[] agreed = {true};
        Cascade.run(() -> {
            List<BlockPos> cells = region.cluster();
            for (BlockPos cell : cells) {
                if (!cell.equals(cut) && !PlayerBlockBreakEvents.BEFORE.invoker()
                        .beforeBlockBreak(level, breaker, cell, level.getBlockState(cell),
                                level.getBlockEntity(cell))) {
                    agreed[0] = false;
                }
            }
        });
        return agreed[0];
    }
}
