package club.rainbowkitty.chopt.chop;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;

/**
 * The player whose own {@code ServerPlayerGameMode.destroyBlock} is running on this thread.
 * Other mods call the break event directly (Axiom, helpers that fake a break); only a real player
 * break may convert a log, so the conversion asks whether it is inside one.
 */
public final class BreakScope {
    private static final ThreadLocal<ServerPlayer> BREAKER = new ThreadLocal<>();

    private BreakScope() {}

    /** Marks {@code player}'s break as running; returns the one it replaced, to put back. */
    public static @Nullable ServerPlayer enter(ServerPlayer player) {
        ServerPlayer outer = BREAKER.get();
        BREAKER.set(player);
        return outer;
    }

    /** Ends the break {@link #enter} began, restoring the one that was running before it. */
    public static void leave(@Nullable ServerPlayer outer) {
        if (outer == null) {
            BREAKER.remove();
        } else {
            BREAKER.set(outer);
        }
    }

    /** The player whose break is running here, or null outside one. */
    public static @Nullable ServerPlayer current() {
        return BREAKER.get();
    }
}
