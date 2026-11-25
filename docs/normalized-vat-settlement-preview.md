# Guarded read-only VAT settlement preview

The opt-in Financial reports tab offers **VAT settlement preview (field-total
policy, NOT POSTED)** as a separate explicit choice. Select a company/year and
inclusive From/To dates, then Generate. No report kind, period or settlement role
is inferred. The previous compatibility and field-total VAT report choices remain
at their existing positions with unchanged accounting amounts.

This is a frozen **proposal**, not a posting action, confirmation token, filing
operation or certified declaration. No voucher number is assigned. There is no
Post/Confirm/Settle button, new CLI command or owner posting entry point. Normal
format-3 startup stays guarded and new databases still default to format 2.

## One immutable owner-held read

`NormalizedFinancialReports.vatSettlementPreview(from,to)` uses the shared
SERIALIZABLE selection-owned read transaction. Null dates fail before opening SQL;
ordered dates must be inside the selected year's current bounds. The read validates
its active selection and uncertainty fences, explicitly hydrates current accounts
and vouchers, invokes only the named field-total `declarationPlan`, copies scalar
values, and rolls back. Its persistence callback always throws if invoked.

The returned `VatSettlementPreview` contains:

- Captured scope, immutable declaration report and explicit proposal date.
- Frozen localized proposal description.
- Immutable `VatSettlementRow` records with raw account number, copied current
  description and exact debit/credit, preserving absent sides as absent.
- Exact debit and credit totals.

It contains **no mutable SSVoucher, account object, writer, number assignment,
commit method or posting token**. The internal plan must be unnumbered, dated at
period end and exactly balanced before it is copied. Later source/mapping/description
changes do not mutate an earlier proposal. Empty sources produce an empty proposal,
not invented zero control rows. Exactly one explicit R1, R2 and A role is required,
even for an empty period; missing/ambiguous roles fail without changing SQL. Plain
read-only declaration reports still work without those roles.

## Display and PDF

The table shows exact clearing rows, control and rounding as produced by the
[explicit declaration policy](explicit-vat-declaration-policy.md), with literal
account descriptions and blank absent sides. The header identifies the policy,
declared payable/refundable total, period/scope, proposed description and exact
balanced totals. Current mappings, inactive-account eligibility and source exclusions
remain those of the named policy. No GUI recalculation or rounding is added.

Explicit Generate is the only refresh. Input/policy changes clear the result and
exportable snapshot; input revisions reject late results after edits. Selection
replacement, owner uncertainty, close and host interlocks retain existing fences.

PDF export reuses the existing immutable displayed-snapshot exporter: the exact
NOT POSTED title, warnings, scope and displayed strings are exported without SQL or
recalculation, to an explicit new destination with existing no-overwrite safeguards.
A later posting does not change the displayed proposal or its PDF. After two source
vouchers with 0.99 on each of two output accounts, a fresh proposal uses declared
control **3** from exact 3.96; an earlier displayed proposal remains control **1**.
This is not a portable posting instruction, transaction receipt or filing document.

## Account/dimension observations

The preview now captures [non-exhaustive account/dimension diagnostics](normalized-vat-settlement-diagnostics.md)
in the same read, alongside the immutable proposal. Visible/exported findings identify
inactive proposed accounts, missing required dimensions and raw VAT-source dimensions
not carried by account-level planning, even when an account nets to zero. An empty
findings list explicitly does **not** approve posting; all gates below still apply.
No arithmetic or source values are changed by these observations.

## Deliberately unfinished posting gates

Arithmetic balance and agreement with the declared fields do **not** establish
bookability. This preview does not approve account activity, required dimensions,
source freshness, duplicate or overlapping settlement periods, period locks,
correction relationships or complete filing rules. The present named planner clears
at account level; it is not a dimension-allocation workflow. These limitations are
stated in the visible/exported warning, not silently treated as successful checks.

Safe posting still needs an owner-controlled confirmation/transaction protocol,
bookability/dimension validation, source revalidation, duplicate-period policy,
number assignment and uncertain-commit handling. Do not pass this frozen proposal
back as authorization to the existing generic mutable-plan writer.

## Regression proof

Nine backend tests cover exact clearing/control/rounding and immutable rows,
no-write planning with legacy JDBC blocked, independent compatibility totals,
period/reversal/opening exclusions, current-mapping and description refresh,
foreign/empty years, missing/ambiguous roles, malformed/current-year/retired scopes,
query/rollback/close failures, uncertain commits and late closed-owner reads.

Nine display tests cover explicit unselected construction, exact rows and warnings,
no posting button or SQL mutation, real proposal PDF without SQL after a later
posting, blank sides, role/period failures, payable/refund/empty proposals,
literal descriptions, foreign replacement, query retry, pending-read/close and
policy-change fences, and shared owner uncertainty. Existing report, PDF, policy
and workspace contracts remain in the validation matrix.

No settlement posting UI, filing, default cutover, guard removal, serialization
retirement or G2/G3/issue #75 technical completion is delivered by this preview.
