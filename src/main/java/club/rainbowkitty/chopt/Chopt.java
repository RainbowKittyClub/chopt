package club.rainbowkitty.chopt;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import club.rainbowkitty.chopt.block.ChoptBlocks;
import club.rainbowkitty.chopt.chop.Conversion;
import club.rainbowkitty.chopt.chop.Falls;
import club.rainbowkitty.chopt.chop.GroupBreaks;
import club.rainbowkitty.chopt.command.ChoptCommand;
import club.rainbowkitty.chopt.hold.ChopPoll;
import club.rainbowkitty.chopt.ledger.PlacedLogs;
import club.rainbowkitty.chopt.pack.FellingModels;

/** Mod entrypoint. */
public class Chopt implements ModInitializer {
    public static final String MOD_ID = "chopt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** {@code chopt:<path>} identifier. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ChoptSettings.register();
        PlacedLogs.register();
        ChoptBlocks.init();
        Conversion.register();
        GroupBreaks.register();
        Falls.register();
        FellingModels.register();
        ChopPoll.register();
        ChoptCommand.register();
    }
}
