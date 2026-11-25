# VAT rounding policy audit

Audit date: 2026-11-24. Baseline: PR #250, merged as
`1d1e052b07a16e77b33881df8ab53302452f6745`.

Follow-up: the separately named [explicit declaration policy](explicit-vat-declaration-policy.md)
implements field-total truncation and matching settlement control for opt-in hydrated-year
backend callers. Existing report/planner, GUI/PDF and CLI compatibility behavior remains
unchanged. A [separately selected read-only field-total view](normalized-vat-declaration-report-view.md)
now exposes the named report policy without settlement planning/posting. The
characterization examples below still describe the compatibility surfaces.

This is a prerequisite investigation, not an accounting-policy change. No
production calculation, stored data, GUI, PDF, filing or posting behavior changes.
Normal format-3 startup remains guarded. The existing guarded VAT view is a
legacy-calculation snapshot, not a validated declaration or settlement preview.

## Sources and limits

1. [Skatteforfarandeforordning (2011:1261), chapter 22 section 1](https://www.riksdagen.se/sv/dokument-och-lagar/dokument/svensk-forfattningssamling/skatteforfarandeforordning-20111261_sfs-2011-1261/)
   was retrieved from the Swedish Parliament's consolidated text. It states:
   "Belopp som avser skatt eller avgift ska anges i hela kronor sa att oretal
   bortfaller" (ASCII transcription; original uses Swedish diacritics).
   Thus tax amounts are stated in whole kronor with the ore discarded, not
   rounded to nearest. This sentence alone does not specify internal ledger
   account grouping or a bookkeeping voucher algorithm.
2. [Skatteverket: Fylla i momsdeklarationen, field 49](https://www.skatteverket.se/foretag/moms/deklareramoms/fyllaimomsdeklarationen.4.3a2a542410ab40a421c80004214.html)
   states that field 49 is fields 10, 11, 12, 30, 31, 32, 60, 61 and 62 added
   together, minus field 48. Field 49 must always be filled in, including zero.
   The page was retrieved through the workspace web-search fetcher; an earlier
   direct curl request failed. The formula is not a direction to truncate the
   combined unrounded VAT-account balances.
3. [Bokio: Oresavrundning i momsdeklarationen](https://www.bokio.se/hjalp/moms-skatt/moms/oresavrundning-i-momsdeklarationen/)
   independently describes dropping ore from declaration amounts. This is
   secondary corroboration, not a substitute for legislation or a complete
   declaration specification.

Some Skatteverket legal-guidance pages returned access-rejection text. They were
not used as evidence. Invoice-total rounding guidance concerns a different
operation and was not used to infer declaration behavior.

The field-total reference below is an explicit interpretation: aggregate the
amount belonging to each declaration field, then discard fractional kronor
(toward zero, including negative corrections). Derive field 49 from those
whole-krona tax fields. The retrieved sources do not validate all existing VAT
code mappings, omitted declaration fields, historical periods, correction filing
rules or the complete tax-base field policy. This audit does not certify filing
compliance.

## Two independent discrepancies

`VatService.sum()` currently discards fractional kronor separately for each
account, then adds those integers into a report field. Splitting one field's
balance across accounts can therefore change the report without changing the
field's total economic amount. This affects the explicit hydrated-year path and
the legacy service path; this audit changes neither.

`SSVATUtil.generateVATVoucherInternal()` clears exact VAT balances, truncates the
combined net amount, and balances the difference on the rounding account. That
can disagree with field 49 even when every field contains only one account.
Fixing per-account report truncation alone cannot resolve this second issue.

Signed settlement control below means credit to payable R2 minus debit to
receivable R1. A zero control means no nonzero receivable/payable amount; existing
zero rows are not reinterpreted. Every generated voucher remains balanced.

| Exact VAT amounts | Current report 49 | Current settlement control | Field-total reference 49 |
| --- | ---: | ---: | ---: |
| Same output field, two accounts: +0.99 and +0.99 | 0 | 1 | 1 |
| Same output field, two accounts: -0.99 and -0.99 | 0 | -1 | -1 |
| Two distinct output fields: +0.99 each | 0 | 1 | 0 |
| Output +12.90; input +5.99 | 7 | 6 | 7 |
| Output +5.99; input +12.90 | -7 | -6 | -7 |
| Same output field: +1.90 and -0.95 | 1 | 0 | 0 |

The last example shows that current reporting can overstate, not merely
understate. The two-distinct-fields example shows why simply pooling all output
VAT before truncation is also insufficient.

`VatRoundingPolicyAuditTest` executes these six examples in four test methods,
using strict raw rows that reject ambient account/validity lookups. The tests
characterize current behavior, independently calculate selected field-total
references, check voucher balance and prohibit writes. Their current-result
assertions are evidence of the discrepancy, **not a compatibility requirement for
a future corrected policy**. A policy fix should deliberately update or replace
these characterization assertions, retaining their counterexamples.

## Required follow-up before guarded settlement activation

- Choose and name an explicit declaration-calculation policy. Keep exact account
  balances and field aggregates separate from whole-krona presentation. Do not
  silently change the legacy compatibility path or historical PDF contracts.
- Define the supported VAT code/field inventory and the treatment of negative
  corrections and tax bases; do not imply a complete declaration from this audit.
- Derive settlement control from the same declared tax fields used for field 49,
  while clearing VAT accounts at their exact amounts. Account for the difference
  explicitly and prove payable, refund, zero, sign-crossing and mixed-code cases.
- Require preview/report/PDF agreement, unchanged source rows, rollback and
  reopen tests, plus packaged and cross-platform validation for any behavior fix.
- Still implement owner transactions, stale-source checks, duplicate-period
  handling, uncertain-commit handling and applicable period restrictions before
  exposing a settlement command. Rounding agreement alone is not safe activation.

No new settlement UI, CLI command, schema migration, default cutover, G2/G3 or
issue #75 technical completion is delivered by this audit.
