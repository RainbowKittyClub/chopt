package club.rainbowkitty.chopt.chop;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Charges a chopping tool for the logs it has taken down. Durability is counted in blocks; only
 * here does a block become points, at the tool's own rate, as vanilla would have charged for
 * breaking the same logs one at a time.
 */
public final class Durability {
    private Durability() {}

    /**
     * The blocks of a tree of {@code logs} that have been paid for once {@code phase} of
     * {@code phases} is complete. The wear is spread over the phases in whole blocks, rounding
     * the running total down, so the last phase leaves exactly the tree's whole count.
     */
    public static int shareThrough(int logs, int phase, int phases) {
        return (int) ((long) logs * phase / phases);
    }

    /**
     * Charges {@code tool} for {@code blocks} logs and returns how many it covered. A tool with
     * less durability left than that is used up and covers what it had; a tool the player no longer
     * carries, or one that wears no more, covers all of them for nothing.
     */
    public static int charge(ServerPlayer player, ItemStack tool, int blocks) {
        if (blocks <= 0) {
            return 0;
        }
        int rate = ChopTool.damagePerBlock(tool);
        if (rate <= 0 || !tool.isDamageableItem() || !carries(player, tool)) {
            return blocks;
        }
        int remaining = tool.getMaxDamage() - tool.getDamageValue();
        int affordable = Math.max(1, (remaining + rate - 1) / rate);
        int covered = Math.min(blocks, affordable);
        tool.hurtAndBreak(covered * rate, player, EquipmentSlot.MAINHAND);
        return covered;
    }

    // Whether this very stack is in the player's hand or pockets; a stack that was dropped, swapped
    // or used up is another object, or empty.
    private static boolean carries(ServerPlayer player, ItemStack tool) {
        if (tool.isEmpty()) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot) == tool) {
                return true;
            }
        }
        return false;
    }
}
