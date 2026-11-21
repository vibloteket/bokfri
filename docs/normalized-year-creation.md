# Explicit normalized accounting-year creation

The opt-in [document workspace](normalized-document-workspace.md) can create an **empty**
accounting year for its explicitly selected company. This is a restricted G2 command,
not production bootstrap, a default format-3 cutover, or completion of #75.

## Workflow

1. Explicitly select and Apply an existing company/year in the dedicated workspace.
2. Load the normalized account-plan catalogue. No template is selected automatically;
   duplicate names remain distinguishable by numeric identity.
3. Enter both inclusive dates in `YYYY-MM-DD` format. Neither date is defaulted.
4. Preview. The owner validates dates, inclusive overlap, company/year scope and template
   existence without modifying storage. The preview shows the company identity, dates,
   template identity and empty-year/no-carry-forward semantics.
5. Confirm once. The owner rechecks the lease and exact company/year/template SQL inputs
   in a serializable transaction, then copies the chosen account plan into a new year.
   No vouchers, opening balances, documents or budgets are carried forward.
6. The available years refresh for that company, but the active document lease and saved
   database selection do not change. Explicitly choose and Apply the new year to use it.

Editing dates or the template invalidates confirmation. A failed operation requires a new
preview. Company or selected-year changes outside the owner require explicit reselection;
changes after preview are rejected rather than silently accepting new inputs. New-year
creation requires an existing selected year; creating a first company/year is outside this slice.

## Ownership and failures

`NormalizedApplicationOwner.accountPlans`, `previewYear` and `createYear` share the same
writer gate and uncertainty boundary as both document families. Preview tokens expose no
mutable account plan and belong to exactly one owner/lease. A token is single-use once
accepted, including after an ordinary transaction failure. Confirmation never reads legacy
JDBC, changes global selections, migrates, seeds or repairs a database.

Late insert failures roll back the entire new year and leave the lease usable. Any thrown
commit attempt (including an unchecked failure, before or after durability) blocks catalogue,
selection, document and year commands until external reconciliation and a new owner.
The workspace retires both document screens and year actions; it cannot retry or reload
away uncertainty. A connection-close failure following a confirmed commit is logged and
returns the successful creation, not a retryable failure.

Closing/replacing the UI invalidates preview and late callbacks, not an already accepted
transaction. Such a transaction finishes with its original company identity. All JDBC runs
on background workers; Swing state and callback fencing run on the EDT.

## Verification

- `NormalizedApplicationYearCreationTest`: read-only preview and complete preservation
  of pre-existing SQL rows, empty independent year and unchanged lease/other company,
  duplicate-name choices, invalid dates/overlap/missing template, stale SQL inputs,
  foreign/replaced/closed leases, late-insert rollback/retry, both durability sides of
  uncertain commit, known-commit close failure and accepted-write completion after close.
- `NormalizedApplicationYearCreationGuiTest`: explicit preview/confirm and year-choice
  refresh without context change, edit invalidation, invalid ISO dates, stale-template
  refusal, replacement clearing and late-callback fencing, and uncertain-commit disabling.
- Both names match the existing `NormalizedApplication*Test` GUI CI selector. Run with a
  real display or Xvfb and `-Dawt.headless=false`; headless skips are not GUI evidence.

Production format-3 startup remains guarded. Company creation, first-year creation,
carry-forward, broader command ownership, backup/restore, parity gates and serialization
retirement remain separate work in the [cutover checklist](normalized-cutover-checklist.md).
