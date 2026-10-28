package club.rainbowkitty.chopt.command;

import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import club.rainbowkitty.chopt.pack.FellingModels;
import club.rainbowkitty.chopt.tree.Family;

/**
 * {@code /chopt probe models}: how many species of log the pack has cropped models for, by family;
 * {@code <block>} says which families one block has them for.
 */
final class ModelsProbe {
    private ModelsProbe() {}

    // The literal to hang under /chopt probe.
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("models")
                .executes(ModelsProbe::summary)
                .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ModelsProbe::one));
    }

    private static int summary(CommandContext<CommandSourceStack> context) {
        Map<Identifier, Map<Family, String>> species = FellingModels.species();
        int[] counts = new int[Family.values().length];
        species.values().forEach(families -> families.keySet()
                .forEach(family -> counts[family.ordinal()]++));
        StringBuilder line = new StringBuilder("models for " + species.size() + " species");
        for (Family family : Family.values()) {
            line.append(", ").append(family).append(' ').append(counts[family.ordinal()]);
        }
        context.getSource().sendSuccess(() -> Component.literal(line.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int one(CommandContext<CommandSourceStack> context) {
        Identifier block = Identifier.tryParse(StringArgumentType.getString(context, "block"));
        Map<Family, String> families = block == null ? null : FellingModels.species().get(block);
        Set<Family> kinds = families == null ? Set.of() : families.keySet();
        context.getSource().sendSuccess(() -> Component.literal(block + ": "
                + (kinds.isEmpty() ? "no model" : "model as " + kinds)), false);
        return Command.SINGLE_SUCCESS;
    }
}
