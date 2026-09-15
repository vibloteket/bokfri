package org.fribok.bookkeeping.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;

/** Builds only the requested top-level command branch, leaving other branches as help entries. */
final class CliCommandTree {
    private CliCommandTree() {
    }

    static CommandLine forArgs(String[] args) {
        CommandLine selector = build(null);
        // Let picocli handle option values, inherited options, '--' and @argument files.
        // Unrecognised branch arguments are expected: only the root is fully modelled here.
        selector.getCommandSpec().parser().collectErrors(true);
        selector.getSubcommands().values().forEach(command -> command.getCommandSpec().parser().collectErrors(true));
        CommandLine.ParseResult parsed;
        try {
            parsed = selector.parseArgs(args);
        } catch (CommandLine.InitializationException | CommandLine.ParameterException exception) {
            // For example, an unreadable @file: let the normal execute path report the original error.
            return fullTree();
        }
        String selected = parsed.hasSubcommand() ? parsed.subcommand().commandSpec().name() : null;
        // Use a fresh root so the selection parse cannot leak option values or parser settings.
        return build(selected);
    }

    /** Full annotation-driven tree for selection failures, compatibility tests and command discovery. */
    static CommandLine fullTree() {
        CommandLine root = new CommandLine(new BokfriCli());
        for (Class<?> type : BokfriCli.SUBCOMMANDS) {
            root.addSubcommand(new CommandLine(type));
        }
        return root;
    }

    private static CommandLine build(String selected) {
        CommandLine root = new CommandLine(new BokfriCli());
        for (Class<?> type : BokfriCli.SUBCOMMANDS) {
            Command metadata = type.getAnnotation(Command.class);
            if (metadata.name().equals(selected)) {
                root.addSubcommand(new CommandLine(type));
            } else {
                CommandSpec summary = CommandSpec.create().name(metadata.name()).aliases(metadata.aliases());
                summary.usageMessage().description(metadata.description()).header(metadata.header())
                        .hidden(metadata.hidden());
                root.addSubcommand(new CommandLine(summary));
            }
        }
        return root;
    }
}
