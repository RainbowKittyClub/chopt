package club.rainbowkitty.chopt.display;

//? if >=26.3 {
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import eu.pb4.factorytools.api.util.LazyItemStack;
import eu.pb4.factorytools.api.util.MiningStageModel;
import eu.pb4.factorytools.api.util.VirtualDestroyStage;
//?}
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import club.rainbowkitty.chopt.Chopt;

/**
 * The cracks over a felling log: for a model, ten more with its shape and a crack texture, one for
 * each stage a vanilla break goes through. They are made by the RKC FactoryTools fork's crack API,
 * which comes with the Polymer patch bundle and is only suggested, so nothing here reads one of its
 * classes unless it is there; without it a felling log shows no cracks. The 26.2 build does not
 * compile against that API and has no cracks.
 */
public final class Cracks {
    private static final String MOD_ID = "factorytools";
    private static final String API = "eu/pb4/factorytools/api/util/MiningStageModel.class";
    private static final boolean AVAILABLE = supported() && FabricLoader.getInstance()
            .isModLoaded(MOD_ID) && Cracks.class.getClassLoader().getResource(API) != null;

    // Static-only; blocks instantiation.
    private Cracks() {}

    // Whether this build was made against the crack API.
    private static boolean supported() {
        //? if >=26.3 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /** Whether FactoryTools and its crack model API are both there. */
    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Readies FactoryTools' crack machinery. It hooks the pack build when its class is first used,
     * and an event does not call a handler added while it runs, so this is called at start-up
     * rather than first from a build that would then miss it.
     */
    public static void init() {
        //? if >=26.3 {
        if (AVAILABLE) {
            try {
                Api.init();
            } catch (RuntimeException | LinkageError e) {
                Chopt.LOGGER.warn("FactoryTools has no usable crack API", e);
            }
        }
        //?}
    }

    /**
     * Asks for the crack models of {@code model}. They are generated into the pack, so this is
     * called while the pack is being built, after the model itself is written.
     */
    public static void request(Identifier model) {
        //? if >=26.3 {
        if (AVAILABLE) {
            try {
                Api.request(model);
            } catch (RuntimeException | LinkageError e) {
                Chopt.LOGGER.warn("No cracks over {}", model, e);
            }
        }
        //?}
    }

    /**
     * The item that shows crack stage {@code stage}, from 0 to 9, over {@code model}, or null when
     * there are no cracks for it: no API, or the pack not built yet.
     */
    public static @Nullable ItemStack stack(Identifier model, int stage) {
        //? if >=26.3 {
        try {
            return AVAILABLE ? Api.stack(model, stage) : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        //?} else {
        /*return null;
        *///?}
    }

    //? if >=26.3 {
    // The part that reads FactoryTools, loaded only when it is there.
    private static final class Api {
        private static final Map<Identifier, List<LazyItemStack>> STAGES =
                new ConcurrentHashMap<>();

        static void init() {
            // Reading a static of the class initializes it.
            VirtualDestroyStage.MODELS.getClass();
        }

        static void request(Identifier model) {
            STAGES.put(model, MiningStageModel.stagesOf(model));
        }

        static @Nullable ItemStack stack(Identifier model, int stage) {
            List<LazyItemStack> stages = STAGES.get(model);
            return stages == null ? null
                    : stages.get(Math.clamp(stage, 0, stages.size() - 1)).get();
        }
    }
    //?}
}
