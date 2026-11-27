# Backend-only read-only VAT settlement source review

The existing guarded NOT POSTED preview/PDF remains a frozen display, not a live
validation result. Equal displayed totals, identical proposed rows or an unchanged
maximum voucher number cannot establish that its inputs are still the same.

`NormalizedFinancialReports.vatSettlementReview(from,to)` now captures the existing
immutable preview and private source state in **one** owner-held SERIALIZABLE,
rollback-only read. `checkVatSettlementSources(review)` compares current state in
another rollback-only read and returns `UNCHANGED` or `CHANGED`.

This is a backend-only read-review primitive. **Neither result approves posting.**
A review is not a posting token, confirmation capability, durable receipt or number
reservation. There is no writer, commit action, new owner posting API, GUI button,
CLI command or schema change. Startup remains guarded and issue #75 remains open.

## Scope and exact comparison

The review is bound by identity to its issuing financial-report instance and its
existing active selection. A handle from another owner—even the same company/year—
is rejected before SQL. Retired, closed, replaced or uncertain-commit-blocked
selections retain the existing fences. Null input dates/handles fail before SQL.

Private source state captures nine scoped tables, with deterministic ordering:

- Selected company and accounting year.
- Selected-year accounts and account-plan metadata.
- Selected-year opening balances, **full vouchers and full voucher rows**.
- Selected-company projects and result units, including unused entries and closure
  metadata.

Comparison uses deeply copied, structured SQL string rows including explicit table
and column/type boundaries, not concatenated keys, displayed totals or a lossy hash.
SQL null remains distinct from empty, whitespace, literal `null` and delimiter-
containing strings. No mutable JDBC objects or source values escape through the
review. The public handle exposes only its original immutable preview.

This is deliberately conservative: all selected-year vouchers are captured, even
outside the preview period; all selected-company catalogue entries and metadata
are included, even when unused. Those changes return `CHANGED`. Other companies'
records and unrelated years do not change the selected-year state, unless shared
selected-company metadata itself changes. This is not a whole-database backup or
schema verification mechanism.

## Lifecycle and limitations

Ordinary `vatSettlementPreview` and other existing report calls do not perform the
nine additional source queries. Review issuance uses the same connection/read as
its captured preview; checks neither recalculate nor replace that old preview.
No number is assigned, reserved or consumed. Repeated checks are allowed.

Query, rollback and connection-close errors fail rather than returning a successful
check. Read failures do not create commit uncertainty; existing uncertainty from a
real posting still blocks all report operations. An owner closed while issuance or
checking is pending cannot return a late successful result.

`UNCHANGED` means **current state equality**, not a monotonic revision or transaction
history. Editing and restoring exact values may compare equal. Another transaction
can change inputs immediately after the check; it is not a lock or a guarantee for
a later write. Complete source correctness, duplicate/overlap and period-lock
policy, bookability and atomic confirmation/numbering/uncertain-commit handling
remain unfinished. Future posting must revalidate within its own transaction and
must never treat this read-only result as authorization.

## Proof and unchanged surfaces

Four pure snapshot tests cover deep immutability, null/empty/raw-key distinctions,
structure/order and malformed containers. Twelve owner/SQL tests cover one read per
call/no writes; equal-preview raw row/header edits; changed amounts, opening balances,
account/plan and catalogue/company metadata; foreign-company/other-year isolation;
outside-period changes; pre-SQL foreign/null/retired fences; uncertain commits;
query/rollback/close failures; lazy nine-query source reads; changed selected-year
bounds; and late owner-close fences. Existing VAT preview/diagnostics/PDF contracts
remain unchanged.

The backend APIs require no UI, planner/grouping/amount/default changes. A separate
[guarded UI consumer](normalized-vat-settlement-source-recheck.md) now captures reviews
on explicit settlement Generate and exposes an explicit source recheck. Its PDF
exports only the original displayed snapshot without SQL, never mutable check
outcomes or posting approval.
