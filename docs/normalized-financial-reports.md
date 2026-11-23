# Guarded trial-balance and account-ledger views

The opt-in normalized workspace exposes a sixth **Financial reports** tab after explicit
company/year Apply. It reads and calculates a trial balance or a selected-account ledger;
it never edits SQL, commits a transaction, exports a file or enables production format-3 startup.

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
