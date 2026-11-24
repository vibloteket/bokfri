# Guarded PDF export of the displayed financial report

The opt-in financial-report tab can export its **last successfully generated display
snapshot** for trial balance, account ledger, balance sheet, income statement or VAT report. Normal
format-3 startup remains guarded; new databases still use format 2.

## Explicit workflow

1. Explicitly select company/year, report kind, dates and (for ledger) an account.
2. Generate the report. Review its scope, table and totals.
3. Select **Export displayed snapshot as PDF**, then explicitly choose a new `.pdf` file
   in an existing directory. There is no inferred filename and no overwrite operation.

The PDF copies immutable displayed strings: report kind, scope, dates, current labels,
whole-year maximum voucher context, all table cells, totals and eligibility notes. It is
labelled as a displayed snapshot, **not a fresh database read or accounting certification**.
Later postings do not silently change that snapshot; explicitly Generate again for new data.
Input/kind/context changes clear the snapshot and disable export until another successful
Generate. Even an empty report can be exported with its scope and totals.

Rendering preserves displayed decimal strings, without rounding or recalculation. Rows are
printed as labelled fields with line wrapping and pagination, rather than using the legacy
branded/grouped report templates. Embedded bundled fonts support Swedish text. This is not
a claim of legacy PDF layout parity, printer/preview support, or additional report kinds.

## Filesystem and lifetime contract

`FinancialReportSnapshotPdf` has no accounting-store dependency. The panel captures the
immutable snapshot on the EDT; rendering and filesystem work run in a background worker.
An explicit destination chooser is the only interactive step. Cancelling it does not write,
clear the report or read SQL. Context replacement/retirement while that modal chooser is open
prevents export acceptance. Host selectors/report commands interlock during export work.

A complete PDF is rendered to a private sibling temporary file before publication. A new hard
link atomically publishes it **only if the destination does not exist**. Existing files,
directories and symlinks are refused, including destinations created during rendering.
There is deliberately no `ATOMIC_MOVE` fallback: some providers replace existing targets
for atomic moves even without `REPLACE_EXISTING`. Filesystems without hard-link support
fail safely without publishing a report. The destination directory must already exist and
must not itself be a symlink; no directory tree is created automatically.

A rendering or publication failure removes the temporary file and never exposes a partial
new report. Cleanup errors are attached to failures; after known successful publication a
cleanup error is logged rather than changing success into an unsafe retry suggestion.
This is atomic visibility/no-clobber behavior, not a crash-durability/fsync guarantee.

Once accepted, export may finish with its original immutable snapshot after panel closure or
context replacement. Late callbacks cannot populate a retired/replaced view. This operation
does not acquire or reset the accounting commit-uncertainty state and never writes SQL.
A blocked/retired context cannot start another export.

## Boundaries and tests

No existing PDF exporter, report formulas, CLI commands, schema or accounting data changes.
No default cutover, general legacy application activation, backup/restore, report printing,
full G2/G3 completion or issue #75 technical-completion claim.

`FinancialReportSnapshotPdfTest` covers real PDF text/decimals/Swedish labels/pagination,
deep snapshot immutability, validation, existing paths and symlinks, publication races,
late rendering failure, cleanup and concurrent no-overwrite publication.
`NormalizedApplicationReportSnapshotPdfGuiTest` covers all four real PDF kinds, no SQL reads,
later postings preserving the exported snapshot, cancellation, input/context invalidation,
modal chooser retirement, explicit retry after destination failure, pending-worker controls,
late accepted export after closure and blocked-owner refusal.
