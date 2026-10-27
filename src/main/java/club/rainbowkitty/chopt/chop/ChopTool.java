package club.rainbowkitty.chopt.chop;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;

/**
 * Which tools chop. Any tool vanilla charges durability to for breaking a block does: an axe,
 * pickaxe, shovel, hoe or sword of any material, or a modded tool built the same way. A bare hand,
 * a stack with no tool component and an unbreakable tool have nothing to charge, so they break a
 * log as one plain block.
 */
public final class ChopTool {
    private ChopTool() {}

    /** Whether {@code stack} is a tool vanilla wears down for a block it breaks. */
    public static boolean isChopTool(ItemStack stack) {
        Tool tool = stack.get(DataComponents.TOOL);
        return tool != null && tool.damagePerBlock() > 0 && stack.isDamageableItem();
    }

    /** The durability one broken block costs {@code stack}: its tool component's rate. */
    public static int damagePerBlock(ItemStack stack) {
        Tool tool = stack.get(DataComponents.TOOL);
        return tool == null ? 0 : tool.damagePerBlock();
    }

    /** Whether {@code player} chops: in survival, not sneaking, with a chop tool in hand. */
    public static boolean chops(ServerPlayer player) {
        return player.gameMode.isSurvival() && !player.isShiftKeyDown()
                && isChopTool(player.getMainHandItem());
    }
}
