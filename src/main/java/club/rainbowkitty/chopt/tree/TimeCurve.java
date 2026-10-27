package club.rainbowkitty.chopt.tree;

import net.minecraft.server.MinecraftServer;

import club.rainbowkitty.chopt.ChoptSettings;

/**
 * How long a felling takes, in vanilla breaks of the log. {@code m(n) = min(cap, 1 + linear * n +
 * weight * ln(1 + n))} for {@code n} logs above; the vanilla break that converts the log is the
 * first of them, so the phases take the other {@code m(n) - 1}.
 */
public final class TimeCurve {
    private TimeCurve() {}

    /** The curve at {@code n} logs, from the server's settings. */
    public static double m(MinecraftServer server, int n) {
        double linear = ChoptSettings.CURVE_LINEAR_HUNDREDTHS.get(server) / 100.0;
        double weight = ChoptSettings.CURVE_LOGARITHMIC_WEIGHT.get(server);
        double cap = ChoptSettings.CURVE_CAP.get(server);
        return Math.min(cap, 1.0 + linear * n + weight * Math.log1p(n));
    }

    /**
     * How many stages a felling of {@code family} goes through at curve value {@code m} and
     * {@code base} of a vanilla break per tick. A stage lasts at least as long as vanilla takes to
     * break one log with the tool, a whole number of ticks and at least one, so a small tree has
     * fewer stages than the family has phases and skips some, and the smallest has one. The
     * tolerance keeps an exact count from rounding down because the tool's speed is a float.
     */
    public static int effectivePhases(Family family, double m, double base) {
        if (base <= 0.0) {
            return family.phases();
        }
        double shortest = Math.max(1.0, Math.ceil(1.0 / base - 1.0E-6));
        int fit = (int) Math.floor((m - 1.0) / base / shortest + 1.0E-6);
        return Math.max(1, Math.min(family.phases(), fit));
    }
}
