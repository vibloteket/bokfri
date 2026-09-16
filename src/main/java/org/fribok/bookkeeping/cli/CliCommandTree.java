package org.fribok.bookkeeping.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lazy catalogue; unselected root branches do not require annotation or field inspection. */
final class CliCommandTree {
    record Entry(String name, Class<?> type) {
    }

    private final Class<?> type;
    private final String name;
    private final String qualifiedName;
    private final List<Entry> entries;
    private CliMetadata.Command metadata;
    private Map<String, CliCommandTree> children;

    private CliCommandTree(Class<?> type, String name, String prefix, List<Entry> entries) {
        this.type = type;
        this.name = name;
        qualifiedName = prefix.isEmpty() ? name : prefix + " " + name;
        this.entries = entries;
    }

    static CliCommandTree root() {
        return new CliCommandTree(BokfriCli.class, "bokfri", "", BokfriCli.SUBCOMMANDS);
    }

    static CliCommandTree of(Class<?> type) {
        return new CliCommandTree(type, declaration(type).name(), "", null);
    }

    private static CliMetadata.Command declaration(Class<?> type) {
        CliMetadata.Command result = type.getAnnotation(CliMetadata.Command.class);
        if (result == null) {
            throw new IllegalArgumentException("Missing command declaration: " + type.getName());
        }
        return result;
    }

    private CliMetadata.Command metadata() {
        if (metadata == null) {
            metadata = declaration(type);
        }
        return metadata;
    }

    Map<String, CliCommandTree> getSubcommands() {
        if (children == null) {
            List<Entry> definitions = entries == null
                    ? java.util.Arrays.stream(metadata().subcommands())
                            .map(child -> new Entry(declaration(child).name(), child)).toList()
                    : entries;
            Map<String, CliCommandTree> result = new LinkedHashMap<>();
            for (Entry entry : definitions) {
                CliCommandTree child = new CliCommandTree(entry.type(), entry.name(), qualifiedName, null);
                if (result.put(entry.name(), child) != null) {
                    throw new IllegalArgumentException("Duplicate command: " + child.qualifiedName());
                }
            }
            children = Collections.unmodifiableMap(result);
        }
        return children;
    }

    Class<?> type() {
        return type;
    }

    String name() {
        return name;
    }

    String qualifiedName() {
        return qualifiedName;
    }

    String description() {
        return String.join(" ", metadata().description());
    }

    boolean hasChildren() {
        return entries == null ? metadata().subcommands().length > 0 : !entries.isEmpty();
    }
}
