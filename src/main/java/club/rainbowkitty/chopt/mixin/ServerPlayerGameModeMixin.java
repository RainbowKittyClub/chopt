package club.rainbowkitty.chopt.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;

import club.rainbowkitty.chopt.chop.BreakScope;

/** Marks the span of a player's own break, so a log is converted only by one. */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {
    @Shadow
    @Final
    protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean chopt$scope(BlockPos pos, Operation<Boolean> original) {
        ServerPlayer outer = BreakScope.enter(player);
        try {
            return original.call(pos);
        } finally {
            BreakScope.leave(outer);
        }
    }
}
