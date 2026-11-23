# Guarded initial opening balances

This opt-in format-3 workspace slice supports **initial insertion only** for an empty
selected accounting year. Normal application startup remains guarded; new databases
still use format 2. This does not complete issue #75 or the G2/G3 cutover gates.

## Explicit workflow

1. Explicitly select company and year in the guarded document workspace.
2. Open the seventh tab, **Initial opening balances**, and load eligible accounts.
   Loading does not select an account or infer an amount.
3. Select each account explicitly and add a signed amount: positive debit, negative
   credit. The UI accepts plain dot-decimal values with at most two fractional digits;
   no exponent, thousands separator, locale inference or rounding.
4. Preview 2–200 unique, nonzero account rows. Debit and credit must balance exactly
   to the cent. The preview identifies company/year IDs, year bounds, stored plan type,
   totals and the exact rows in the noneditable table. Descriptions are CURRENT labels.
5. Confirm separately. Confirmation inserts the previewed balances atomically and clears
   the draft after success. Use the read-only financial reports to verify reopened balances.

Any existing opening-balance row (even zero) or journal header (even with no rows)
blocks loading, preview and insertion. There is no overwrite, merge, carry-forward,
rounding adjustment, balancing-account default, year-setting change or journal creation.
Only active selected-year balance accounts classified by an explicit recognized stored
account-plan type are eligible. Missing/unknown plan types are refused, not defaulted to BAS95.
Account/result classification follows the existing opening-balance validation service.
Amounts and debit totals must fit the normalized `NUMERIC(72,2)` storage range.

## Transaction and lifetime contract

`Selection.openingBalances()` owns the command context. Read-only catalogue/preview
operations roll back, never commit. Confirmation uses the owner's serializable gate,
schema-validated connection and shared uncertain-commit observer. It revalidates scope,
empty-year eligibility and a full snapshot of selected company/year/plan/accounts before
inserting. Previews are opaque, owner-bound and single-use, including rejected confirmation
attempts; catalogue drift requires a new preview. Data belonging to another year/company
is neither included in the token nor changed.

A late insert failure rolls back every new row. A commit failure blocks all owner command
families: reconcile externally and open a new owner, rather than blindly retrying. A known
successful commit followed by connection-close failure remains a successful insert. An
already accepted transaction may finish against its original scope after owner retirement.
The service's replacement API is not used: it has a different commit boundary.

The panel performs JDBC/validation off the EDT and fences late callbacks on replacement or
closure. Draft edits invalidate confirmation; duplicate clicks and host context changes
are interlocked during work. Closing the panel does not close the owner's lease or cancel
an accepted transaction. Workspace uncertainty retires this panel with the other views.

## Boundaries and evidence

No legacy application frame, general account editing, replacement balances, year closing,
backup/restore, migration, CLI mutation, schema change or default cutover is enabled.

Regression coverage is in `NormalizedApplicationOpeningBalancesTest` and
`NormalizedApplicationOpeningBalancesGuiTest`: exact decimals and active-account choices,
empty-year refusal, full old-row preservation, reports/reopen, stale and foreign tokens,
late rollback/retry, commit uncertainty, close errors, retirement, explicit UI choices,
validation errors, worker interlocks, late-callback fencing and seventh-tab installation.
Full regression, packaged-JAR contracts and CLI smoke evidence must pass before publication.
