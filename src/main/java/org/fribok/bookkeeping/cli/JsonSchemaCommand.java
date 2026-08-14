package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** Base for database-independent schema subcommands. */
abstract class JsonSchemaCommand implements Callable<Integer> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private final String name;
    private final Class<?> inputType;

    JsonSchemaCommand(String name, Class<?> inputType) {
        this.name = name;
        this.inputType = inputType;
    }

    @Override
    public Integer call() throws Exception {
        JsonNode schema = new CliJsonSchemaService().generate(name, inputType);
        spec.commandLine().getOut().println(BokfriCli.jsonMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(schema));
        return 0;
    }
}
