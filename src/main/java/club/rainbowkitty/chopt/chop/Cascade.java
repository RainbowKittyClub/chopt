package club.rainbowkitty.chopt.chop;

/**
 * Whether this thread is inside chopt's own removal of a tree. The felling asks the break event
 * for each log, so claim mods can veto it; those calls must not convert a log again.
 */
public final class Cascade {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private Cascade() {}

    /** Whether a cascade is running on this thread. */
    public static boolean active() {
        return ACTIVE.get() != null;
    }

    /** Runs {@code action} as a cascade. */
    public static void run(Runnable action) {
        Boolean outer = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            if (outer == null) {
                ACTIVE.remove();
            }
        }
    }
}
