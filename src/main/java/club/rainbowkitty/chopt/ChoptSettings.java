package club.rainbowkitty.chopt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;

import club.rainbowkitty.kalic0re.common.config.ModConfig;
import club.rainbowkitty.kalic0re.common.config.Setting;

/**
 * This mod's server settings. Each is a setting of the rkcore-common settings hub, and a game rule
 * of the same id where the hub is absent. None is cached: read them where they are used.
 */
public final class ChoptSettings {
    private static final ModConfig CONFIG = ModConfig.create(Chopt.MOD_ID);

    // Every setting declared below, in declaration order, for values().
    private static final List<Setting<Integer>> ALL = new ArrayList<>();

    /** Most logs one felling may take down; a tree with more is not staged. */
    public static final Setting<Integer> MAX_LOGS = range("max_logs", 256, 1, 4096);

    /**
     * Most logs the hang-from scan visits before it gives up. A cut whose connected logs exceed
     * this stages nothing.
     */
    public static final Setting<Integer> SCAN_CAP = range("scan_cap", 2048, 1, 65536);

    /**
     * The time curve's linear term, in hundredths of a vanilla break per log above:
     * {@code m(n) = min(cap, 1 + linear / 100 * n + weight * ln(1 + n))}.
     */
    public static final Setting<Integer> CURVE_LINEAR_HUNDREDTHS =
            range("curve_linear_hundredths", 12, 0, 1000);

    /** The time curve's logarithmic weight, in vanilla breaks. */
    public static final Setting<Integer> CURVE_LOGARITHMIC_WEIGHT =
            range("curve_logarithmic_weight", 2, 0, 100);

    /** The time curve's ceiling, in vanilla breaks, so a huge tree is never longer than this. */
    public static final Setting<Integer> CURVE_CAP = range("curve_cap", 32, 2, 1000);

    /**
     * Ticks a felling log may sit untouched before it reverts to the log it was, when nothing
     * has been charged for it.
     */
    public static final Setting<Integer> IDLE_REVERT_TICKS =
            range("idle_revert_ticks", 2400, 20, 1_728_000);

    // Static-only; blocks instantiation.
    private ChoptSettings() {}

    /**
     * Declares every setting and registers them with the hub or as game rules. Call once from the
     * initializer, before the game-rule registry freezes.
     */
    public static void register() {
        CONFIG.register();
    }

    /** Every setting's key and its value on {@code server}, in declaration order. */
    public static Map<String, Integer> values(MinecraftServer server) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Setting<Integer> setting : ALL) {
            values.put(setting.key(), setting.get(server));
        }
        return values;
    }

    // Declares a whole-number server setting in [min, max], with a game rule of its id where the
    // hub is absent.
    private static Setting<Integer> range(String key, int defaultValue, int min, int max) {
        Setting<Integer> setting =
                CONFIG.range(key, defaultValue, min, max).server().fallbackRule();
        ALL.add(setting);
        return setting;
    }
}
