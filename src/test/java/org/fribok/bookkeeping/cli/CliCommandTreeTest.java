package org.fribok.bookkeeping.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CliCommandTreeTest {
    @TempDir
    Path directory;

    @Test
    void onlyTheSelectedTopLevelBranchIsInspected() {
        CommandLine command = CliCommandTree.forArgs(new String[]{"company", "list"});

        assertThat(command.getSubcommands().get("company").getCommandSpec().userObject())
                .isInstanceOf(BokfriCli.CompanyCommand.class);
        assertThat(command.getSubcommands().get("company").getSubcommands()).containsKey("list");
        assertThat(command.getSubcommands().get("voucher").getCommandSpec().userObject()).isNull();
        assertThat(command.getSubcommands().get("voucher").getSubcommands()).isEmpty();
        assertThat(command.getSubcommands()).hasSameSizeAs(CliCommandTree.fullTree().getSubcommands());
    }

    @Test
    void rootHelpDoesNotInspectAnyCommandBranch() {
        CommandLine command = CliCommandTree.forArgs(new String[]{"--help"});

        assertThat(command.getSubcommands().values()).allSatisfy(child -> {
            assertThat(child.getCommandSpec().userObject()).isNull();
            assertThat(child.getSubcommands()).isEmpty();
        });
    }

    @Test
    void everyHelpPageAndUnknownOptionMatchesTheFullTreeExactly() {
        List<List<String>> paths = new ArrayList<>();
        collectPaths(CliCommandTree.fullTree(), List.of(), paths);
        assertThat(paths).hasSize(141);
        for (List<String> path : paths) {
            for (String suffix : List.of("--help", "-h", "--not-a-bokfri-option")) {
                List<String> args = new ArrayList<>(path);
                args.add(suffix);
                assertEquivalent(args.toArray(String[]::new));
            }
        }
    }

    @Test
    void rootOptionsDelimitersAndErrorsMatchTheFullTree() {
        List<String[]> cases = List.of(
                new String[]{},
                new String[]{"version"},
                new String[]{"--format", "json", "version"},
                new String[]{"version", "--format=json"},
                new String[]{"--format", "json", "--format", "text", "version"},
                new String[]{"--data-dir", "voucher", "version"},
                new String[]{"--config", "company", "--format", "text", "voucher", "--help"},
                new String[]{"--company-id=1", "voucher", "list", "--help"},
                new String[]{"--company-id", "-1", "version"},
                new String[]{"--company-id", "oops", "company", "list", "--help"},
                new String[]{"--company-id", "company", "list", "--help"},
                new String[]{"--format=jsno", "company", "list", "--help"},
                new String[]{"--format"},
                new String[]{"--help", "company", "list"},
                new String[]{"company", "--help", "list"},
                new String[]{"--", "version"},
                new String[]{"--", "--help"},
                new String[]{"-hV"},
                new String[]{"--hlep"},
                new String[]{"compani"},
                new String[]{"company", "lits"},
                new String[]{"version", "company"},
                new String[]{"company"},
                new String[]{"voucher", "list", "--limit", "oops"},
                new String[]{"voucher", "list", "--limit"},
                new String[]{"voucher", "list", "--from=not-a-date"},
                new String[]{"voucher", "list", "--", "--help"},
                new String[]{"company", "list", "--data-dir", "voucher", "--help"});
        for (String[] args : cases) {
            assertEquivalent(args);
        }
    }

    @Test
    void argumentFilesUsePicocliExpansionAndQuoting() throws Exception {
        Path argsFile = directory.resolve("arguments with spaces.txt");
        Files.writeString(argsFile, "--data-dir \"company with spaces\"\nvoucher list --help\n");
        assertEquivalent("@" + argsFile);

        Path outer = directory.resolve("outer.txt");
        Files.writeString(outer, "@\"" + argsFile + "\"\n");
        assertEquivalent("@" + outer);
        assertEquivalent("@@" + argsFile);
        assertEquivalent("@" + directory.resolve("missing.txt"));
        assertEquivalent("@" + directory);

        Files.writeString(argsFile, "--company-id invalid company list --help\n");
        assertEquivalent("@" + argsFile);
    }

    private static void assertEquivalent(String... args) {
        // Run both at the same call site, so even diagnostic stack traces can be compared verbatim.
        List<Result> results = List.of(CliCommandTree.fullTree(), CliCommandTree.forArgs(args)).stream()
                .map(command -> execute(command, args)).toList();
        assertThat(results.get(1)).as("Arguments: %s", List.of(args)).isEqualTo(results.get(0));
    }

    private static Result execute(CommandLine command, String[] args) {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int code = BokfriCli.execute(command, args, new PrintWriter(stdout, true), new PrintWriter(stderr, true));
        return new Result(code, stdout.toString(), stderr.toString());
    }

    private static void collectPaths(CommandLine command, List<String> prefix, List<List<String>> paths) {
        paths.add(prefix);
        command.getSubcommands().forEach((name, child) -> {
            List<String> path = new ArrayList<>(prefix);
            path.add(name);
            collectPaths(child, List.copyOf(path), paths);
        });
    }

    private record Result(int code, String stdout, String stderr) {
    }
}
