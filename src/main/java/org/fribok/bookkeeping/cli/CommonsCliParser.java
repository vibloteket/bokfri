package org.fribok.bookkeeping.cli;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.help.TextHelpAppendable;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** Commons CLI parsing plus Bokfri's small command router and selected-field binding layer. */
final class CommonsCliParser {
    private final CliCommandTree root;
    private final Object rootObject;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Map<CliCommandTree, BoundCommand> bound = new LinkedHashMap<>();
    private final Set<Field> assignedValues = new HashSet<>();
    private CliContext help;
    private boolean version;

    CommonsCliParser(CliCommandTree root, Object rootObject, PrintWriter out, PrintWriter err) {
        this.root = root;
        this.rootObject = rootObject;
        this.out = out;
        this.err = err;
    }

    int execute(String[] arguments) throws Exception {
        BoundCommand current = bind(root, rootObject, null, List.of());
        String[] remaining = arguments;
        while (true) {
            Map<String, Binding> names = names(current.options());
            String[] normalized = normalizeBooleans(remaining, names, current);
            CommandLine parsed;
            try {
                DefaultParser.NonOptionAction action = current.context().node().hasChildren()
                        ? DefaultParser.NonOptionAction.STOP : DefaultParser.NonOptionAction.SKIP;
                parsed = DefaultParser.builder().setAllowPartialMatching(false)
                        .setStripLeadingAndTrailingQuotes(false).get()
                        .parse(options(current.options()), null, action, normalized);
            } catch (ParseException exception) {
                throw syntax(current, exception.getMessage());
            }
            for (Option option : parsed.getOptions()) {
                String name = option.getLongOpt() == null ? "-" + option.getOpt() : "--" + option.getLongOpt();
                Binding binding = names.get(name);
                if (!binding.flag() && !assignedValues.add(binding.field())) {
                    throw syntax(current, "Option " + binding.name() + " may only be specified once");
                }
                String value = option.getValue();
                if (!binding.flag() && current.context().node().getSubcommands().containsKey(value)) {
                    throw syntax(current, "Expected a value for " + binding.name() + " but found command '" + value + "'");
                }
                Object converted = convert(binding.type(), value, binding.name(), current);
                if (binding.role().equals("help")) {
                    if (help == null) {
                        help = current.context();
                    }
                } else if (binding.role().equals("version")) {
                    version = true;
                } else {
                    binding.field().set(binding.target(), converted);
                }
            }
            String[] rest = parsed.getArgs();
            boolean endOfOptions = false;
            // In a command group Commons stops at the first non-option; everything after it is verbatim.
            for (int i = 0; i < normalized.length - rest.length; i++) {
                endOfOptions |= normalized[i].equals("--");
            }
            if (current.context().node().hasChildren() && rest.length > 0) {
                CliCommandTree child = endOfOptions ? null : current.context().node().getSubcommands().get(rest[0]);
                if (child == null) {
                    throw syntax(current, "Unmatched argument: '" + rest[0] + "'");
                }
                Constructor<?> constructor = child.type().getDeclaredConstructor();
                constructor.setAccessible(true);
                List<Binding> inherited = current.options().stream().filter(Binding::inherited).toList();
                current = bind(child, constructor.newInstance(), current.instance(), inherited);
                remaining = Arrays.copyOfRange(rest, 1, rest.length);
                continue;
            }
            if (rest.length > current.positions().size()) {
                throw syntax(current, "Unmatched argument: '" + rest[current.positions().size()] + "'");
            }
            for (int i = 0; i < rest.length; i++) {
                Position position = current.positions().get(i);
                position.field().set(current.instance(), convert(position.field().getType(), rest[i],
                        "<" + position.field().getName() + ">", current));
            }
            if (help != null) {
                printHelp(help, out);
                return 0;
            }
            if (version) {
                // Preserve the existing empty standard version-help flag; the `version` command prints details.
                return 0;
            }
            for (BoundCommand command : bound.values()) {
                for (Binding binding : command.options()) {
                    if (binding.required() && !assignedValues.contains(binding.field())) {
                        throw syntax(command, "Missing required option: " + binding.name());
                    }
                }
            }
            if (rest.length < current.positions().size()) {
                throw syntax(current, "Missing required parameter: <"
                        + current.positions().get(rest.length).field().getName() + ">");
            }
            if (current.instance() instanceof Callable<?> callable) {
                return (Integer) callable.call();
            }
            ((Runnable) current.instance()).run();
            return 0;
        }
    }

    void report(CliSyntaxException exception) {
        err.println(exception.getMessage());
        printHelp(exception.context(), err);
    }

    private BoundCommand bind(CliCommandTree node, Object instance, Object parent, List<Binding> inherited)
            throws ReflectiveOperationException {
        CliContext context = new CliContext(out, err, node);
        List<Binding> options = new ArrayList<>(inherited);
        List<Position> positions = new ArrayList<>();
        BoundCommand result = new BoundCommand(instance, context, options, positions);
        bound.put(node, result);
        for (Class<?> type = node.type(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                CliMetadata.Option option = field.getAnnotation(CliMetadata.Option.class);
                CliMetadata.Parameters parameter = field.getAnnotation(CliMetadata.Parameters.class);
                boolean injectParent = field.isAnnotationPresent(CliMetadata.ParentCommand.class);
                boolean injectContext = field.isAnnotationPresent(CliMetadata.Spec.class);
                if (option == null && parameter == null && !injectParent && !injectContext) {
                    continue;
                }
                field.setAccessible(true);
                if (injectParent) {
                    field.set(instance, parent);
                } else if (injectContext) {
                    field.set(instance, context);
                } else if (option != null) {
                    Binding binding = new Binding(option.names(), String.join(" ", option.description()),
                            option.hidden(), option.required(), option.scope() == CliMetadata.ScopeType.INHERIT,
                            field, instance, "");
                    options.add(binding);
                    if (!option.defaultValue().equals(CliMetadata.NO_DEFAULT)) {
                        field.set(instance, convert(field.getType(), option.defaultValue(), binding.name(), result));
                    }
                } else {
                    positions.add(new Position(Integer.parseInt(parameter.index()), field,
                            String.join(" ", parameter.description())));
                }
            }
        }
        positions.sort(Comparator.comparingInt(Position::index));
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).index() != i) {
                throw new IllegalArgumentException("Non-contiguous positional indices in " + node.qualifiedName());
            }
        }
        options.add(new Binding(new String[]{"-h", "--help"}, "Show this help message and exit.",
                false, false, false, null, null, "help"));
        options.add(new Binding(new String[]{"-V", "--version"}, "Print version information and exit.",
                false, false, false, null, null, "version"));
        names(options); // Fail on ambiguous application declarations rather than silently shadowing a field.
        return result;
    }

    private static Map<String, Binding> names(List<Binding> bindings) {
        Map<String, Binding> result = new LinkedHashMap<>();
        for (Binding binding : bindings) {
            for (String name : binding.names()) {
                if (result.put(name, binding) != null) {
                    throw new IllegalArgumentException("Duplicate option declaration: " + name);
                }
            }
        }
        return result;
    }

    private static Options options(List<Binding> bindings) {
        Options result = new Options();
        for (Binding binding : bindings) {
            Option.Builder builder = Option.builder().hasArg();
            for (String name : binding.names()) {
                if (name.startsWith("--")) {
                    builder.longOpt(name.substring(2));
                } else {
                    builder.option(name.substring(1));
                }
            }
            result.addOption(builder.get());
        }
        return result;
    }

    /** Preserve zero-arity flags and explicit boolean values while leaving actual parsing to Commons CLI. */
    private static String[] normalizeBooleans(String[] input, Map<String, Binding> names, BoundCommand command) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < input.length; i++) {
            String token = input[i];
            if (token.equals("--") || (!token.startsWith("-") && command.context().node().hasChildren())) {
                result.addAll(Arrays.asList(input).subList(i, input.length));
                break;
            }
            int equals = token.indexOf('=');
            String name = equals < 0 ? token : token.substring(0, equals);
            Binding binding = names.get(name);
            if (binding != null) {
                if (binding.flag()) {
                    result.add(name);
                    result.add(equals < 0 || equals == token.length() - 1 ? "true" : token.substring(equals + 1));
                } else {
                    result.add(token);
                    if (equals < 0 && i + 1 < input.length) {
                        result.add(input[++i]);
                    }
                }
            } else if (!command.context().node().hasChildren() && negativeNumber(token)) {
                // Match the existing treatment of negative numeric positional values.
                result.add(token);
            } else if (token.startsWith("-") && !token.startsWith("--") && token.length() > 2) {
                for (int j = 1; j < token.length(); j++) {
                    String flag = "-" + token.charAt(j);
                    Binding part = names.get(flag);
                    if (part == null || !part.flag()) {
                        throw syntax(command, "Unrecognized option: " + flag);
                    }
                    result.add(flag);
                    String tail = token.substring(j + 1);
                    if (tail.startsWith("=")) {
                        result.add(tail.length() == 1 ? "true" : tail.substring(1));
                        break;
                    }
                    if (tail.equalsIgnoreCase("true") || tail.equalsIgnoreCase("false")) {
                        result.add(tail);
                        break;
                    }
                    result.add("true");
                }
            } else {
                if (token.startsWith("-") && !token.equals("-")) {
                    throw syntax(command, "Unrecognized option: " + token);
                }
                result.add(token);
            }
        }
        return result.toArray(String[]::new);
    }

    private static boolean negativeNumber(String token) {
        if (token.length() < 2 || token.charAt(0) != '-') {
            return false;
        }
        try {
            Double.parseDouble(token);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static Object convert(Class<?> type, String value, String name, BoundCommand command) {
        try {
            if (type == String.class) {
                return value;
            }
            if (type == int.class || type == Integer.class) {
                return Integer.valueOf(value);
            }
            if (type == boolean.class || type == Boolean.class) {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("expected true or false");
                }
                return Boolean.valueOf(value);
            }
            if (type == Path.class) {
                return Path.of(value);
            }
            if (type == LocalDate.class) {
                return LocalDate.parse(value);
            }
            if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                for (Object constant : constants) {
                    if (((Enum<?>) constant).name().equals(value)) {
                        return constant;
                    }
                }
                throw new IllegalArgumentException("expected one of " + Arrays.stream(constants)
                        .map(constant -> ((Enum<?>) constant).name()).toList());
            }
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw syntax(command, "Invalid value for " + name + ": '" + value + "' (" + exception.getMessage() + ")");
        }
        throw new IllegalArgumentException("Unsupported CLI field type: " + type.getName());
    }

    private void printHelp(CliContext context, PrintWriter writer) {
        BoundCommand command = bound.get(context.node());
        Options visible = new Options();
        for (Binding binding : command.options()) {
            if (binding.hidden()) {
                continue;
            }
            Option.Builder option = Option.builder().desc(binding.helpDescription());
            for (String name : binding.names()) {
                if (name.startsWith("--")) {
                    option.longOpt(name.substring(2));
                } else {
                    option.option(name.substring(1));
                }
            }
            if (!binding.flag()) {
                option.hasArg().argName(binding.field().getName()).required(binding.required());
            }
            visible.addOption(option.get());
        }
        String syntax = context.node().qualifiedName();
        for (Position position : command.positions()) {
            syntax += " <" + position.field().getName() + ">";
        }
        TextHelpAppendable text = new TextHelpAppendable(writer);
        text.setMaxWidth(100);
        text.setLeftPad(0);
        HelpFormatter formatter = HelpFormatter.builder().setHelpAppendable(text).setShowSince(false).get();
        formatter.setSyntaxPrefix("Usage:");
        try {
            formatter.printHelp(syntax, context.node().description(), visible, "", true);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
        if (!command.positions().isEmpty()) {
            writer.println("Arguments:");
            for (Position position : command.positions()) {
                writer.printf("  %-24s %s%n", "<" + position.field().getName() + ">", position.description());
            }
        }
        if (context.node().hasChildren()) {
            writer.println("Commands:");
            for (CliCommandTree child : context.node().getSubcommands().values()) {
                writer.printf("  %-24s %s%n", child.name(), child.description());
            }
        }
        writer.flush();
    }

    private static CliSyntaxException syntax(BoundCommand command, String message) {
        return new CliSyntaxException(command.context(), message);
    }

    private record BoundCommand(Object instance, CliContext context, List<Binding> options, List<Position> positions) {
    }

    private record Position(int index, Field field, String description) {
    }

    private record Binding(String[] names, String description, boolean hidden, boolean required, boolean inherited,
                           Field field, Object target, String role) {
        Class<?> type() {
            return field == null ? boolean.class : field.getType();
        }

        boolean flag() {
            return type() == boolean.class || type() == Boolean.class;
        }

        String name() {
            return names[names.length - 1];
        }

        String helpDescription() {
            if (type().isEnum()) {
                String choices = String.join(", ", Arrays.stream(type().getEnumConstants())
                        .map(Object::toString).toList());
                return description.replace("${COMPLETION-CANDIDATES}", choices);
            }
            return description;
        }
    }
}
