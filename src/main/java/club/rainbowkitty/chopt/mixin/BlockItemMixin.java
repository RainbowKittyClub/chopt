package club.rainbowkitty.chopt.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.ledger.PlacedLogs;

/** Records every log a block item places, whoever the placer is. */
@Mixin(BlockItem.class)
public class BlockItemMixin {
    // The one setPlacedBy call in place(), reached with a null or fake placer too.
    @WrapOperation(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)"
            + "Lnet/minecraft/world/InteractionResult;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;setPlacedBy("
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/item/ItemStack;)V"))
    private void chopt$markPlaced(Block block, Level level, BlockPos pos, BlockState state,
            LivingEntity placer, ItemStack stack, Operation<Void> original) {
        original.call(block, level, pos, state, placer, stack);
        PlacedLogs.placed(level, pos, state);
    }
}
