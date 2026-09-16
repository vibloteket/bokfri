# CLI parser migration

The Commons CLI migration is on the feature branch, not yet released. The compatibility and help-policy changes below were approved during the migration review.

## Approved compatibility changes

- Do not expand `@argument` files. Tokens and values beginning with `@` are ordinary input; normal `--file` import paths remain supported.
- Value-bearing options may occur only once in the entire invocation, even when the same value is repeated or the option appears before and after a subcommand.
- Boolean switches may repeat. Explicit `true`/`false`, including `--apply=false`, remain supported. Parsing/validation completes before any command body runs.

## Architecture

`CommonsCliParser` delegates token parsing to Apache Commons CLI 1.11.0 and supplies Bokfri's routing, field binding, help context and invocation-wide duplicate checks. It inspects fields only for the selected root/group/command instances. It does not inspect every command method or instantiate unused branches.

`CliMetadata` is deliberately limited to Bokfri's current declarations: commands, scalar options, scalar positionals, parent injection and output context. It is not a general-purpose CLI framework. Avoid adding a second extensible parser or an argument-file language here.

The explicit root catalogue provides names without inspecting other command annotations. A test checks that catalogue names match command declarations. Subcommand declarations remain beside their command code. Full help may load other descriptions, but never executes those commands.

Global options are available before or after the selected command. The same root field is bound in either location, allowing duplicate checks to identify the option consistently. Commons CLI does not natively implement picocli's zero-arity boolean syntax; a bounded normalization step preserves explicit boolean values and short help clusters without allowing a following positional word to be consumed as an optional boolean value.

The business command bodies, database lifecycle, explicit-apply/commit checks, JSON schemas and domain-error response shapes are retained. Parser syntax errors use exit 2; expected command errors use exit 1; unexpected execution errors retain diagnostic logging and exit 70.

## Other syntax retained

- Long `--flag=value` syntax and standard short help/version clusters.
- `--` stops option handling/command dispatch rather than accidentally executing a command after it.
- Negative numeric positional arguments and literal quoted values.
- No command or long-option abbreviation.
- Existing rejection of command-looking option values at command-group level.
- Root with no command still reports usage and exit 2.
- Existing empty standard `--version` output is retained for now; the `version` command prints version/build information.

## Approved help policy

The new human-readable help uses Commons CLI's tabular formatter. It still lists all commands/options and keeps hidden options hidden, but layout and syntax-error wording differ.

The parser validates unmatched input even when help was requested. For example, `bokfri version --unknown --help` reports the unknown option, prints help, and exits 2. The old picocli path printed help and exited 0, masking unknown/extra arguments. Both versions allow `--help` without otherwise-required inputs and reject malformed explicitly supplied typed values.

The stricter unmatched-input behavior was approved on 2026-09-16. Invalid invocations show the error and help on stderr with exit 2, without executing business code. Valid help uses stdout and exit 0, including when otherwise-required inputs are missing.

## Verification

Keep coverage for all 141 command paths and the existing application workflows. In addition, test invocation-wide duplicates, literal `@` input, boolean false/apply safety, flag clusters, delimiters, negative positionals, raw quotes and default values. Run the full Maven verification and CLI black-box smoke test, then platform CI before release.

Benchmark real before/after JARs in shuffled fresh JVMs. Report time to first output separately from process exit; do not promise the standalone Commons prototype's ~39 ms as the whole application's startup time.
