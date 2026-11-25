# Guarded explicit-period VAT report

**Rounding limitation:** [the policy audit](vat-rounding-policy-audit.md) records
account-splitting and report/settlement discrepancies in the retained calculation.
This view and its PDF are legacy-calculation snapshots, not validated declaration
or settlement previews. The audit changes no displayed amounts. The separately named
[explicit declaration policy](explicit-vat-declaration-policy.md) is now available as
a [separate guarded read-only choice](normalized-vat-declaration-report-view.md).
This compatibility choice retains its existing amounts; its label now explicitly
states per-account truncation. Each choice's PDF uses its own displayed snapshot.

The opt-in normalized Financial reports tab also offers **VAT report**. Explicitly select
company/year, this report kind and inclusive From/To dates inside the selected year, then
Generate. There is no inferred month/quarter/year period, account selection or automatic
refresh. Input/kind/context changes clear the result. Normal format-3 startup remains guarded;
new databases still use format 2.

The read-only view displays all 16 existing Swedish VAT boxes, including zero amounts,
VAT-code groups and the signed payable (+) / refundable (-) total. Box 49 is calculated from
output VAT boxes minus input VAT box 48; it is not a sum of R1/R2 account movements.

## Existing calculation rules, made explicit

- Inclusive-period eligible voucher rows only; stored opening balances and earlier/later
  entries are excluded. Original postings and reversals both participate.
- Resolve raw account numbers against the selected year's CURRENT account plan and CURRENT,
  case-sensitive VAT-code mappings. Missing/unresolved/empty/crossed rows are excluded;
  eligible historical added rows and inactive accounts remain reportable.
- Aggregate each account first, then truncate its signed total toward zero to whole currency
  units **before** summing code groups into boxes. This is not rounding the combined box total,
  rounding each voucher/row, or changing stored decimal amounts.
- Output/turnover groups use credit minus debit; purchase/input groups use debit minus credit.
  Missing amounts on one side act as zero, following the existing explicit voucher aggregator.
- No automatic currency conversion, rate/mapping inference, filing certification, submitted-period
  tracking or exclusion of settlement vouchers. Review currency, mappings, source data and any
  earlier settlement before using these informational totals for filing.

The shared `VatService` retains the legacy SSDB constructor's existing report path. A supplied
hydrated-year context now uses the existing explicit-account voucher aggregators, not singleton
row account/validity getters or mutating account binding. Duplicate/unknown explicit account
numbers fail validation rather than resolving ambiguously. Formula/code groups and per-account
truncation remain unchanged. Existing explicit-input settlement and legacy VAT regressions apply;
no new settlement operation is exposed by this view.

## Owner and snapshot boundaries

`Selection.reports().vatReport(from,to)` uses the existing lease-owned serializable read
transaction, schema-validated connection, complete selected-year hydration, shared gate,
rollback (never commit), availability checks and context/closure fences. No accounting writes
or VAT writer is available to the UI. Read errors do not mark a commit uncertain; an owner's
uncertain commit elsewhere blocks these reads with the other command families.

Generation runs off the EDT; UI rendering and immutable display capture run on the EDT.
Existing host interlocks and late-callback fencing apply. Explicit **Export displayed snapshot
as PDF** exports exactly the displayed VAT boxes, scope, rounding notes and total, without a new
SQL read or extra rounding. Later postings/mapping changes do not alter that snapshot. Generate
again deliberately for new data. Existing new-file-only/no-overwrite publication rules apply;
see [normalized-report-snapshot-pdf.md](normalized-report-snapshot-pdf.md).

## Scope and regression evidence

No VAT settlement voucher, filing/transmission, period lock, legacy VAT frame, legacy PDF layout,
printing, preview, schema/CLI command change or default cutover. This does not complete G2/G3
or the technical definition of done for issue #75.

`ExplicitVatReportServiceTest` verifies every code group, signs, per-account truncation,
negative values, dates, inactive/added/null/unresolved/crossed/empty rows, duplicate account
refusal and strict rejection of ambient/mutating row getters. Application and real-display
VAT report tests cover unchanged SQL, legacy-JDBC blocking, scope/period isolation, opening
exclusion, postings/reversal, current mapping refresh, immutable snapshots, worker/retirement
fences, read failures, commit uncertainty and real VAT snapshot PDF export without SQL access.


## VAT settlement planning isolation (2026-11-24)

The existing hydrated-year/explicit-input VAT planner now resolves only supplied account
numbers and creates an unnumbered voucher, leaving numbering to its explicit writer.
The legacy SSDB overload retains its existing lookup/numbering path; amounts and rounding
are unchanged. See [normalized-vat-settlement-isolation.md](normalized-vat-settlement-isolation.md).
This is a backend prerequisite, not settlement UI activation, a new posting command,
period locking/filing, or G2/G3/#75 completion. The guarded VAT view remains read-only.
