package club.rainbowkitty.chopt.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;

import club.rainbowkitty.chopt.Chopt;
import club.rainbowkitty.chopt.ChoptSettings;
import club.rainbowkitty.kalic0re.common.command.CommandPermissions;

/**
 * {@code /chopt probe}: what the mod reads, for checking it. Bare, it lists the settings; each
 * stage adds a subcommand, starting with {@code ledger}. Brigadier merges every {@code /chopt}
 * branch.
 */
public final class ChoptCommand {
    private static final CommandPermissions PERMISSIONS = CommandPermissions.forMod(Chopt.MOD_ID);

    private ChoptCommand() {}

    /** Registers the command. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, build, environment) ->
                dispatcher.register(Commands.literal(Chopt.MOD_ID)
                        .then(Commands.literal("probe")
                                .requires(PERMISSIONS.require("probe", PermissionLevel.GAMEMASTERS))
                                .executes(ChoptCommand::settings)
                                .then(LedgerProbe.node())
                                .then(TreeProbe.node())
                                .then(FellingProbe.node(build))
                                .then(HoldProbe.node())
                                .then(ProgressProbe.node())
                                .then(ModelsProbe.node()))));
    }

    // Lists every setting's value as this server reads it.
    private static int settings(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ChoptSettings.values(source.getServer()).forEach((key, value) ->
                source.sendSuccess(() -> Component.literal(key + " = " + value), false));
        return Command.SINGLE_SUCCESS;
    }
}
