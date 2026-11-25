# Explicit whole-krona VAT declaration policy

## Explicit policy and read-only adapters

A hydrated-year `VatService` now offers explicitly named `declarationReport(from, to)`
and `declarationPlan(from, to)` methods. The SSDB-backed constructor rejects these
methods before any legacy data read. This policy is deliberately **not** selected by
existing `report(...)`, `plan(...)`, CLI commands, or the guarded compatibility VAT
view and its PDF.
Those compatibility surfaces retain their previous amounts, mappings and rounding.
A separately selected [guarded field-total policy view](normalized-vat-declaration-report-view.md)
now invokes the read-only declaration report through the lease-owned report adapter;
its displayed-snapshot PDF identifies that policy. The compatibility choice remains
available and explicitly labelled. No planner or writer is exposed by this view.
A separate [read-only settlement preview](normalized-vat-settlement-preview.md) now
calls the named planner inside the owner-held read and returns immutable scalar
proposal rows, not a mutable plan or posting token. Its exported warnings explicitly
exclude bookability, dimensions, stale-source and duplicate-period approval.
No settlement posting action is activated; normal format-3 startup remains guarded.

The [rounding audit](vat-rounding-policy-audit.md) records the source basis and its
limits. This implementation selects the audit's field-total interpretation; it is
not a complete filing specification or certification. The field inventory is the
existing supported 16-box subset, not a claim to cover every declaration field.

## Calculation contract

- Inputs are the supplied hydrated year's current account codes and voucher rows,
  an explicit inclusive period and known unique account numbers. Invalid dates,
  null/unknown/duplicate account-plan numbers are rejected under the same input
  rules as the explicit compatibility report. In particular, null account numbers
  are invalid; rows referring to numbers outside the plan are ignored as before.
- Aggregation resolves raw row numbers against the supplied plan, never ambient
  `getAccount`/`isValid` calls or mutating row-account binding. Crossed, unknown,
  null-number, empty, undated and out-of-period rows retain existing eligibility.
  Inactive-account and added rows remain eligible. Opening balances are not source
  vouchers. This service does not independently enforce accounting-year bounds;
  a future owner entry point must validate its selected scope and period.
- Sum **exact account balances within each field**, then discard fractional kronor
  toward zero once for the field. Do not round to cents first. A negative correction
  of -1.99 becomes -1, not -2. Opposite signs cancel before truncation.
- Box 49 is the sum of declared boxes 10, 11, 12, 30, 31, 32, 60, 61 and 62, minus
  declared box 48. It is not a truncation of raw net VAT or a sum of R1/R2 movements.
- Tax-base fields use the same selected field-total policy, but this is a scoped
  implementation decision, not a claim that all tax-base reporting rules have been
  independently established by the tax-amount statute quoted in the audit.
- The returned report/box list is immutable. Subsequent changes to supplied mutable
  objects require a fresh calculation and do not rewrite an earlier report.

| Field | Existing supported codes | Direction |
| --- | --- | --- |
| 5 | MP1, MP2, MP3, PTOG | credit minus debit |
| 10 | U1, UVL | credit minus debit |
| 11, 12 | U2; U3 | credit minus debit |
| 20, 21, 22 | VFEU; TFEU; TFFU | debit minus credit |
| 30 | U1MI, UEU, UTFU | credit minus debit |
| 31, 32 | U2MI; U3MI | credit minus debit |
| 48 | I, IVL | debit minus credit |
| 50 | IBU, IBU1, IBU2, IBU3 | credit minus debit |
| 60, 61, 62 | UI1; UI2; UI3 | credit minus debit |
| 49 | derived from the tax fields above | payable positive / refund negative |

## Settlement-plan contract

Planning requires **exactly one explicitly coded account for each of R1, R2 and A**,
including when the period is empty. It does not infer those roles from account
numbers 1650/2650/3740. A report alone does not require settlement-role accounts.

The planner derives its tax-account code inventory from the report's tax fields,
not a separate legacy clearing-code list. It reverses each nonzero exact balance
belonging to fields 10/11/12/30/31/32/48/60/61/62, in selected-account order. Thus
U1MI/U2MI/U3MI are included, unlike the compatibility helper's narrower inventory.
Tax bases, R1/R2/A source movements and unknown codes are not cleared as tax rows.

The control amount equals box 49: credit payable R2 if positive, debit receivable
R1 if negative, and **no control row if zero**. Let `exactNet` be the sum of exact
credit-minus-debit tax balances. The rounding row reverses `box49 - exactNet`
(debit if positive, credit if negative), only when nonzero. This keeps the voucher
exactly balanced, even if exactNet is zero but box 49 is nonzero, or if box 49 is
zero but exactNet has a fraction. Fractional cents are not silently discarded.

Plans have number **0**, the explicit period end date and the existing localized
period description. No database reads/writes or automatic numbering occur during
calculation. The existing explicit writer can number/persist the mutable plan; no
new persistence or posting entry point is introduced here.

## Safety and validation boundaries

These methods work over caller-supplied mutable hydration, not an owner-held SQL
snapshot. There is no single-use token, concurrent/stale-source check, period lock,
duplicate-settlement check, transaction ownership or uncertain-commit guarantee.
The generic existing `commit` method is not upgraded into a safe settlement command.
Do not wire this policy into a new UI/CLI posting action without those safeguards.
The separately selected read-only view identifies the policy and preserves displayed
values in its PDF. A future settlement preview/posting workflow still requires its own
owner-held transaction, source checks and report/preview agreement; it is not delivered
by the read-only view.

`ExplicitVatDeclarationPolicyTest` checks all six audit examples, every supported
VAT tax code (including the three formerly omitted clearing groups), tax-base
exclusion, zero/net-zero, exact fractions, signed corrections, role validation,
source eligibility, immutable reports and unchanged compatibility results.
`NormalizedApplicationVatDeclarationPolicyTest` hydrates SQL with legacy JDBC
blocked, proves no-write planning, explicit writer numbering, exact balanced
clearing after reopen, unchanged source vouchers, foreign-year isolation and caller
rollback after a late writer failure. This writer test is not an owner-atomicity
or production activation claim.

No migration, GUI/CLI activation, legacy PDF change, normal startup cutover,
serialization retirement, G2/G3 completion or issue #75 technical completion.
