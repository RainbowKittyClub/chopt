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
     * How many phases a felling of {@code family} goes through at curve value {@code m} and
     * {@code base} of a vanilla break per tick: all of them, or one when the whole felling is
     * shorter than the phases would be, so a small tree falls at the first completed phase.
     */
    public static int effectivePhases(Family family, double m, double base) {
        if (base <= 0.0) {
            return family.phases();
        }
        return (m - 1.0) / base < family.phases() ? 1 : family.phases();
    }
}
