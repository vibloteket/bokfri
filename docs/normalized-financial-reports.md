# Guarded financial-report views and snapshot export

The opt-in normalized workspace exposes a sixth **Financial reports** tab after explicit
company/year Apply. It reads trial balance, account ledger, balance sheet, income statement
and an explicit-period informational VAT report.
It never edits SQL, commits an accounting transaction or enables production format-3 startup.
An explicit new-file action can export the displayed snapshot as PDF without re-reading SQL;
see [normalized-report-snapshot-pdf.md](normalized-report-snapshot-pdf.md).

## Explicit workflow

1. Apply the company/year. No report kind, dates or account are selected, and no report is read.
2. Choose **Trial balance** and enter both dates explicitly, then Generate. Both endpoints must
   be inside the leased accounting year and From must not exceed Through (inclusive period).
3. For **Account ledger**, explicitly Load the current selected-year account catalogue and choose
   an account, even when only one exists. Inactive accounts remain reportable. Enter both dates
   and Generate; the account must still belong to the year at read time.
4. Inspect the noneditable table and context/totals header. Changing kind, dates or account clears
   the previous result; read errors do not leave an old report displayed as if current. Replacing
   the context, closing, or commit uncertainty clears results and old choices.
5. Generate again after posting, reversal or other changes. Reports are read snapshots, not live
   subscriptions. They never auto-refresh, select a journal, change document context or invent an
   opening balance. The raw voucher register remains available for inspecting stored values.

## Calculation semantics

These views reuse `FinancialReportService` formulas with explicit selected-year account resolution:

- Opening at From = stored year opening balances plus eligible entries dated before From.
- Period debit and credit cover both inclusive endpoints. Closing = opening + debit - credit.
  Trial-balance rows with opening, debit, credit and closing all zero are omitted. Header totals
  show the corresponding sums; no balance correction or certification is implied.
- Account-ledger rows are chronological by voucher date, then voucher number, retaining original
  row order within a voucher. Each row shows stored voucher description, debit/credit and running
  balance. Multiple rows in one voucher remain separate. Empty ledgers still show opening/closing.
- A row is eligible when its account number resolves in the selected plan, at least one amount
  exists, and it is not crossed. Inactive accounts, added rows and historical edit metadata do
  not exclude otherwise eligible accounting entries. Null-dated vouchers, unresolved accounts
  and rows with neither amount are excluded. A null amount side is zero for calculation/display,
  as in the existing service; the raw register preserves the original null/zero distinction.
- No rounding, VAT/balancing entries, currency conversion or dimension filtering is performed.
  Raw decimal precision is retained. Original and reversal journals are both included, so an
  exact reversal cancels net movement while both sides remain visible in turnover/ledger rows.
- Account and company descriptions are **current** metadata, not historical labels. Strings are
  quoted/escaped so null, empty, newline and HTML-like text are not silently reinterpreted.

Each result includes company/year identities, current company name, requested period and the
maximum voucher number in the entire year read snapshot (including undated/out-of-period
headers). That maximum is context only, not a data revision, a period maximum or a promise that
later data cannot change. No missing catalogue label or currency code is filled from legacy defaults.

## Ownership and explicit calculation boundary

`Selection.reports()` returns lease-owned `NormalizedFinancialReports`. It loads the selected
company/year, account plan, opening balances and all year vouchers in one serializable read
transaction using the owner's schema-validated connections and shared transaction gate. Known
matching year dates and a plan are required; foreign/changed/retired/closed/blocked scopes fail
closed. No connection is held between requests. Read transactions roll back and close; no commit
is attempted. Query/rollback/close errors are therefore read errors, not uncertain writes.

The new `FinancialReportService.withExplicitAccounts(year)` factory resolves raw row account
numbers against that year plan without calling ambient `getAccount()`/`isValid()` or mutating row
account bindings. The existing legacy constructor retains its original behavior. Normalized CLI
financial reports also use this explicit factory; legacy CLI reports keep the legacy constructor.
Existing JSON and balance/income/ledger PDF parity tests exercise that shared CLI route. This
GUI slice itself exposes only trial balance and account ledger, not PDF or additional reports.

JDBC and calculations run off the EDT. A pending report interlocks host Reload/Apply,
company/year/manual/reversal commands and register reads without polling SQL. Context replacement
uses the shared gate; owner closure retires a pending read and prevents returning its payload.
The panel fences old callbacks and does not own the database/window lifetime. Uncertain commits
elsewhere retire and clear the view pending reconciliation and a new application owner.

## Verification and remaining gates

- `ExplicitFinancialReportServiceTest`: legacy/explicit formula parity, strict failures on ambient
  or mutating row resolvers, exact opening/period/running balances, null/crossed/missing rows,
  deterministic ordering, date/account validation, malformed plans and immutable result lists.
- `NormalizedApplicationFinancialReportsTest`: read-only transaction/SQL observations, unchanged
  database snapshots, opening/prior-period/post/reversal reconciliation, exact decimals and row
  eligibility, current/null labels, company isolation, refreshed versus old snapshots, stale year
  scopes, retired/blocked readers, read failures and shared-gate/closure races. Legacy JDBC is blocked.
- `NormalizedApplicationFinancialReportsGuiTest`: explicit inputs/no defaults, read-only results,
  input invalidation, inactive/one-choice accounts, replacement, recovery, pending interlocks,
  closure fencing and uncertainty retirement. Existing workspace tests verify all six tabs.
- `BokfriCliTest#financialReportsWorkOnNormalizedStorage` compares legacy and normalized JSON and
  representative PDF outputs through the changed CLI construction path. GUI evidence requires
  Xvfb/a real display; headless skips alone do not prove the views.

These are bounded read-only accounting views, not complete legacy report GUI parity, PDF/export
UI, dimension-specific reporting, editing, backup/restore, production integration or full G1–G4.
Production format-3 startup remains guarded; new databases remain format 2. #75 technical
completion is not claimed. See the [cutover checklist](normalized-cutover-checklist.md).


## Guarded balance-sheet and income-statement views (2026-11-23)

The existing financial-report tab also offers two explicit, read-only report kinds:

- **Balance sheet:** enter the inclusive **To** date; **From** is disabled and unused.
  Rows show signed debit-minus-credit balances from stored year opening and eligible
  entries through that date. Totals expose assets, liabilities/equity, current-year
  result and difference. Current result excludes opening amounts; the visible difference
  is assets minus liabilities/equity minus current result, not an accounting certification.
  No rounding or adjustment is applied to force a zero difference.
- **Income statement:** enter explicit inclusive **From/To** dates. Only period result-account
  movement is included, credit minus debit. Opening amounts and earlier entries are excluded.
  The period result is shown; there is no inferred comparison period or balancing adjustment.

Zero rows are omitted by the existing shared calculator. Current catalogue labels, inactive
accounts and eligible historical rows follow the same explicit-account resolution as trial
balance/ledger. Generate is explicit; changing inputs clears results without automatically
reading storage. Both kinds use the same owner lease, serializable read/rollback transaction,
background worker, stale-callback fencing, host interlock and uncertain-commit block.

No new tab, PDF/export UI, write command, legacy-frame integration, schema migration,
dimension filtering, backup/restore or default cutover is introduced. Format-3 normal
startup remains guarded and format-2 creation is unchanged. These views do not complete
G2/G3 or the technical definition of done for issue #75.


## Guarded displayed-report snapshot PDF (2026-11-23)

The financial-report tab can export its successfully generated immutable display snapshot
as a new PDF, with explicit destination selection and atomic no-overwrite publication.
No SQL re-read, recalculation, rounding or accounting write occurs during export.
Input/context changes disable export until Generate succeeds again. Accepted exports may
finish against their original snapshot after retirement; late UI callbacks are fenced.
See [normalized-report-snapshot-pdf.md](normalized-report-snapshot-pdf.md) for file safety,
hard-link filesystem requirements, snapshot/legacy-layout boundaries and regression tests.
This does not activate normal format-3 startup or establish full G2/G3/#75 completion.


## Guarded read-only VAT report (2026-11-24)

The existing financial-report tab now supports explicit inclusive-period VAT boxes and
VAT snapshot PDF export, with current account-code mappings and the existing per-account
whole-unit truncation rules. The lease-owned read rolls back and never commits; normalized
VAT aggregation uses explicit row account numbers, not legacy account/validity getters.
No settlement, filing, currency conversion, new tab, schema/CLI command or default cutover.
See [normalized-vat-report-view.md](normalized-vat-report-view.md) for exact rules and bounds.
This does not complete G2/G3 or issue #75 technical requirements; startup guard stays enabled.
