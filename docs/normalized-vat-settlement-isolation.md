# Explicit-input VAT settlement planning: legacy isolation

**Rounding follow-up:** [the policy audit](vat-rounding-policy-audit.md) identifies
independent account-aggregation and net-truncation discrepancies. The parity
preserved below is not a validated declaration policy. No settlement activation
is implied by balanced vouchers or explicit-input isolation. The opt-in
[explicit declaration policy](explicit-vat-declaration-policy.md) provides separate
`declarationReport`/`declarationPlan` methods without changing either helper overload
or existing service `report`/`plan` behavior. It is not a new posting action.

The hydrated-year VAT service and the explicit-input `SSVATUtil.generateVATVoucher(...)`
overload now plan only from supplied accounts and vouchers. They resolve raw row account
numbers against that selected plan, without ambient row account/validity getters or mutating
account binding. The result uses the existing explicit voucher constructor with number **0**
and the explicit period end date: planning does not read the legacy year, clock, last voucher
or numbering state. An explicit persistence writer must assign the real voucher number.

The legacy overload still reads SSDB and assigns the next legacy number. Its aggregation,
localized description, period date, amounts, row ordering and rounding behavior are retained.
The existing normalized CLI writer already assigns the next selected-year number before
insertion/commit. No CLI command or writer behavior changes in this fix.

## Amounts and rounding are intentionally unchanged

Settlement reverses the eligible VAT-account movements for the inclusive period, then
truncates the **combined** signed VAT-account sum toward zero to whole units for the
receivable/payable row. The difference goes to the existing rounding account. When the
combined sum is zero but VAT accounts have offsetting balances, only those VAT clearing rows
are generated; no control or rounding row is invented. An entirely empty plan cannot commit.

This is distinct from the existing VAT **report**, which truncates each account before
combining it into boxes. For example, two output-VAT accounts at 0.99 each produce a report
total of 0, while the existing settlement plan clears 1.98 with a 1-unit payable row and
0.98 rounding row. This fix deliberately does **not** reconcile or silently change either
calculation rule. The separate VAT-control printing helper's rounding is also unchanged.
Currency/mapping/source review remains necessary before any settlement or filing.

## Not a new posting workflow

This is a backend dependency-isolation prerequisite, not VAT settlement GUI activation.
The opt-in VAT report remains read-only. No new command, UI panel, settlement confirmation,
period tracking/locking, filing/transmission, currency conversion, schema change or default
format-3 cutover is introduced. Normal startup remains guarded; new databases use format 2.
This does not complete G2/G3 or the technical definition of done for issue #75.

Existing service plans contain mutable domain vouchers and delegate persistence to a caller's
writer. This fix does not add owner-bound single-use tokens, stale-source checks, transaction
ownership, duplicate-period prevention or shared uncertain-commit handling to that API. Those
safeguards must be designed and verified before exposing a new guarded posting workflow.

## Regression evidence

`ExplicitVatSettlementIsolationTest` rejects ambient/mutating source-row getters, verifies
payable/refund signs, exact clearing/control/rounding rows, unnumbered plans, explicit writer
assignment, aggregate-vs-report truncation, inactive/added/crossed/invalid/date eligibility,
unchanged source rows, zero-net clearing and empty-plan refusal.

`NormalizedApplicationVatSettlementIsolationTest` plans against hydrated normalized data with
legacy JDBC blocked, verifies unchanged SQL during planning, explicit SQL-number assignment
and reopen, source/foreign-year preservation, and caller rollback after a late writer failure.
The legacy VAT integration regression also asserts that legacy numbering remains assigned.
Existing VAT report, explicit settlement, VAT math and PDF printer tests cover compatibility.
