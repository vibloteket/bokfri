package org.fribok.bookkeeping.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class CliCommandTreeTest {
    @TempDir
    Path directory;

    @Test
    void explicitRootNamesMatchTheirCommandDeclarations() {
        assertThat(CliCommandTree.root().name()).isEqualTo(BokfriCli.class
                .getAnnotation(CliMetadata.Command.class).name());
        for (CliCommandTree.Entry entry : BokfriCli.SUBCOMMANDS) {
            assertThat(entry.name()).isEqualTo(entry.type().getAnnotation(CliMetadata.Command.class).name());
        }
    }

    @Test
    void bindsOnlyInstancesOnTheSelectedPath() throws Exception {
        Unused.constructions = 0;
        Root root = new Root();
        Result result = execute(root, "group", "run", "--company-id", "2");
        assertThat(result.code()).isZero();
        assertThat(root.calls).isEqualTo(1);
        assertThat(root.companyId).isEqualTo(2);
        assertThat(Unused.constructions).isZero();
    }

    @Test
    void rootHelpDoesNotInstantiateOtherCommands() throws Exception {
        Unused.constructions = 0;
        Root root = new Root();
        Result result = execute(root, "--help");
        assertThat(result.code()).isZero();
        assertThat(result.out()).startsWith("Usage:").contains("group", "unused", "show");
        assertThat(root.calls).isZero();
        assertThat(Unused.constructions).isZero();
    }

    @Test
    void rejectsRepeatedValueFlagsBeforeOrAcrossCommandsEvenWhenEqual() throws Exception {
        for (String[] args : List.of(
                new String[]{"--company-id", "1", "--company-id", "2", "group", "run"},
                new String[]{"--company-id=1", "group", "run", "--company-id=1"},
                new String[]{"group", "--company-id", "1", "run", "--company-id", "2"})) {
            Root root = new Root();
            Result result = execute(root, args);
            assertThat(result.code()).isEqualTo(2);
            assertThat(result.err()).contains("--company-id", "only be specified once");
            assertThat(root.calls).isZero();
        }
    }

    @Test
    void repeatableBooleanFlagsAndExplicitFalseRemainSafe() throws Exception {
        Root root = new Root();
        Result result = execute(root, "--verbose", "group", "run", "--verbose=false", "--apply", "--apply=false");
        assertThat(result.code()).isZero();
        assertThat(root.verbose).isFalse();
        assertThat(root.applied).isZero();
        assertThat(root.calls).isEqualTo(1);

        Root repeated = new Root();
        assertThat(execute(repeated, "--verbose", "--verbose", "group", "run", "--apply=TRUE").code()).isZero();
        assertThat(repeated.verbose).isTrue();
        assertThat(repeated.applied).isEqualTo(1);

        Root empty = new Root();
        assertThat(execute(empty, "--verbose=", "group", "run").code()).isZero();
        assertThat(empty.verbose).isTrue();
    }

    @Test
    void aSeparateBooleanWordDoesNotBecomeAnOptionalFlagValue() throws Exception {
        Root root = new Root();
        assertThat(execute(root, "--verbose", "false", "group", "run").code()).isEqualTo(2);
        assertThat(root.calls).isZero();
        assertThat(execute(new Root(), "group", "run", "--apply=wrong").code()).isEqualTo(2);
    }

    @Test
    void helpClustersAndExplicitHelpValuesPreserveExistingBehaviour() throws Exception {
        for (String help : List.of("-h", "--help", "-hV", "-Vh", "-htrue", "-h=false", "--help=false", "-hV=false", "-hVfalse")) {
            Root root = new Root();
            Result result = execute(root, help);
            assertThat(result.code()).as(help).isZero();
            assertThat(result.out()).startsWith("Usage:");
            assertThat(root.calls).isZero();
        }
        assertThat(execute(new Root(), "-hxyz").code()).isEqualTo(2);
        assertThat(execute(new Root(), "--version").out()).isEmpty();
    }

    @Test
    void argumentFilesAreNeverExpandedButOrdinaryAtValuesAreAccepted() throws Exception {
        Path file = directory.resolve("arguments.txt");
        Files.writeString(file, "group run --apply\n");
        Root root = new Root();
        assertThat(execute(root, "@" + file).code()).isEqualTo(2);
        assertThat(root.calls).isZero();
        assertThat(execute(root, "--label", "@" + file, "group", "run").code()).isZero();
        assertThat(root.label).isEqualTo("@" + file);
        assertThat(root.applied).isZero();
    }

    @Test
    void delimiterStopsDispatchAndAllowsLiteralFlagLookingPositionals() throws Exception {
        Root root = new Root();
        assertThat(execute(root, "--", "group", "run").code()).isEqualTo(2);
        assertThat(root.calls).isZero();
        assertThat(execute(root, "show", "--", "-h").code()).isZero();
        assertThat(root.shown).isEqualTo(Path.of("-h"));
    }

    @Test
    void flagsAfterPositionalsAndNegativeIdentifiersWork() throws Exception {
        Root root = new Root();
        assertThat(execute(root, "show", "-1", "--verbose").code()).isZero();
        assertThat(root.shown).isEqualTo(Path.of("-1"));
        assertThat(root.verbose).isTrue();
        assertThat(execute(new Root(), "show", "--unknown").code()).isEqualTo(2);
    }

    @Test
    void negativeNumericPathsAndLiteralQuotesAreNotReinterpreted() throws Exception {
        for (String value : List.of("-1.2", "-1e3", "-Infinity", "-NaN")) {
            Root root = new Root();
            assertThat(execute(root, "show", value).code()).isZero();
            assertThat(root.shown).isEqualTo(Path.of(value));
        }
        Root root = new Root();
        assertThat(execute(root, "--label", "\"literal quotes\"", "group", "run").code()).isZero();
        assertThat(root.label).isEqualTo("\"literal quotes\"");
    }

    @Test
    void helpSkipsMissingArgumentsButNotInvalidSuppliedValues() throws Exception {
        assertThat(execute(new Root(), "show", "--help").code()).isZero();
        assertThat(execute(new Root(), "show").code()).isEqualTo(2);
        assertThat(execute(new Root(), "--company-id", "wrong", "--help").code()).isEqualTo(2);
        assertThat(execute(new Root(), "--company-id").code()).isEqualTo(2);
    }

    @Test
    void enumErrorsListValuesWithoutExposingImplementationNames() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = BokfriCli.execute(new String[]{"--format", "invalid", "version"},
                new PrintWriter(out, true), new PrintWriter(err, true));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("expected one of [text, json]").doesNotContain("org.fribok");
    }

    @Test
    void commandLookingValuesKeepTheirExistingRejectionUntilPolicyChanges() throws Exception {
        assertThat(execute(new Root(), "--label", "group", "group", "run").code()).isEqualTo(2);
        assertThat(execute(new Root(), "--label=group", "group", "run").code()).isEqualTo(2);
        Root root = new Root();
        assertThat(execute(root, "group", "run", "--label=group").code()).isZero();
        assertThat(root.label).isEqualTo("group");
    }

    private static Result execute(Root root, String... args) throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommonsCliParser parser = new CommonsCliParser(CliCommandTree.of(Root.class), root,
                new PrintWriter(out, true), new PrintWriter(err, true));
        int code;
        try {
            code = parser.execute(args);
        } catch (CliSyntaxException exception) {
            parser.report(exception);
            code = 2;
        }
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {
    }

    @CliMetadata.Command(name = "test", subcommands = {Group.class, Show.class, Unused.class})
    static class Root implements Runnable {
        @CliMetadata.Option(names = "--company-id", scope = CliMetadata.ScopeType.INHERIT, defaultValue = "1")
        int companyId;
        @CliMetadata.Option(names = "--verbose", scope = CliMetadata.ScopeType.INHERIT)
        boolean verbose;
        @CliMetadata.Option(names = "--label", scope = CliMetadata.ScopeType.INHERIT)
        String label;
        @CliMetadata.Spec CliContext context;
        int calls;
        int applied;
        Path shown;
        public void run() { throw new CliSyntaxException(context, "A command is required"); }
    }

    @CliMetadata.Command(name = "group", subcommands = Run.class)
    static class Group implements Runnable {
        @CliMetadata.ParentCommand Root root;
        @CliMetadata.Spec CliContext context;
        public void run() { throw new CliSyntaxException(context, "A group command is required"); }
    }

    @CliMetadata.Command(name = "run")
    static class Run implements Callable<Integer> {
        @CliMetadata.ParentCommand Group group;
        @CliMetadata.Option(names = "--apply") boolean apply;
        public Integer call() {
            group.root.calls++;
            if (apply) { group.root.applied++; }
            return 0;
        }
    }

    @CliMetadata.Command(name = "show")
    static class Show implements Callable<Integer> {
        @CliMetadata.ParentCommand Root root;
        @CliMetadata.Parameters(index = "0") Path file;
        public Integer call() { root.shown = file; root.calls++; return 0; }
    }

    @CliMetadata.Command(name = "unused")
    static class Unused implements Runnable {
        static int constructions;
        Unused() { constructions++; }
        public void run() { throw new AssertionError("unused command executed"); }
    }
}
