package club.rainbowkitty.chopt.chop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;

import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.tree.Family;

/**
 * Who chops, and what a chop costs. A player in survival chops with whatever they hold, a fist
 * included, at the speed that item breaks the log. Only a tool vanilla charges durability to for
 * breaking a block wears down, at its own rate; a bare hand, a stick or an unbreakable tool chop
 * for nothing.
 */
public final class ChopTool {
    private ChopTool() {}

    /**
     * Whether {@code player} is in survival, which is the only mode that chops. Adventure is not:
     * vanilla's own checks keep it from breaking blocks it may not.
     */
    public static boolean survival(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
    }

    /** Whether {@code stack} wears down for a block it breaks, as vanilla charges a tool. */
    public static boolean wears(ItemStack stack) {
        return damagePerBlock(stack) > 0 && stack.isDamageableItem();
    }

    /** The durability one broken block costs {@code stack}: its tool component's rate. */
    public static int damagePerBlock(ItemStack stack) {
        Tool tool = stack.get(DataComponents.TOOL);
        return tool == null ? 0 : tool.damagePerBlock();
    }

    /** Whether {@code player} chops: in survival and not sneaking. */
    public static boolean chops(ServerPlayer player) {
        return survival(player) && !player.isShiftKeyDown();
    }

    /**
     * Whether {@code player} chops the felling log at {@code pos}. Sneaking only matters for a log
     * that stands alone, which a sneaking player breaks as the plain log it was; a started 2x2
     * group is not broken up by hand, so it is chopped whether the player sneaks or not.
     */
    public static boolean chopsAt(ServerPlayer player, BlockGetter level, BlockPos pos) {
        if (!survival(player)) {
            return false;
        }
        return !player.isShiftKeyDown()
                || level.getBlockEntity(pos) instanceof FellingLogBlockEntity log
                        && log.family() == Family.GROUP;
    }
}
