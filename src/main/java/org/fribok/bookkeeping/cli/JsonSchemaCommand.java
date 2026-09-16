package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.Callable;

/** Base for database-independent schema subcommands. */
abstract class JsonSchemaCommand implements Callable<Integer> {
    @CliMetadata.Spec
    CliContext spec;

    private final String name;
    private final Class<?> inputType;

    JsonSchemaCommand(String name, Class<?> inputType) {
        this.name = name;
        this.inputType = inputType;
    }

    @Override
    public Integer call() throws Exception {
        JsonNode schema = new CliJsonSchemaService().generate(name, inputType);
        spec.out().println(BokfriCli.jsonMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(schema));
        return 0;
    }
}
