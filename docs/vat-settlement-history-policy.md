# VAT settlement history policy (#75)

## Fail closed for unknown history

Existing vouchers have no authoritative structured VAT-settlement-period history. A newly empty
registry would mean **unknown**, not **unsettled**. Descriptions, VAT account balances, zero totals,
and matching source snapshots must not silently establish a reviewed baseline.

The agreed policy is that future posting must be blocked unless explicit reviewed history covers
**every date** of the requested period, and no known settlement overlaps that period. Reviewed
coverage may include known settlements; coverage is not itself a claim that all those dates are
unsettled. Known overlapping settlements always block, even within fully reviewed coverage.

## First slice: pure policy only

`VatSettlementHistory` evaluates explicit immutable inputs without SQL, ambient selection, clock,
locale, voucher inspection, numbering or writes:

- `Scope`: explicit legacy company/year selection identifiers and inclusive fiscal-year bounds.
  These identifiers are opaque integers (including zero), not SQL BIGINT row identities.
- `Period`: valid inclusive `LocalDate` endpoints, including a single day.
- `Snapshot`: copied lists of reviewed coverage and known settlement periods for that exact scope.
- `inspect`: copied findings for every unreviewed gap and every known overlapping settlement.

Intervals may be adjacent, unordered, nested or duplicated. Reviewed coverage is considered as a
union without modifying the original lists. A one-day gap remains unknown. Known settlements sharing
an endpoint with the request overlap; immediately adjacent ones do not. Duplicate known facts are
retained, never treated as cancellations or reversals. Gap findings are in calendar order, followed
by full conflicting known periods in input order. Nothing infers a voucher reference.

Missing inputs, malformed periods, scope mismatches (including fiscal bounds), and dates outside the
specified fiscal year are rejected. Out-of-year facts are not silently clipped or ignored. Both
unreviewed coverage and known overlaps are reported when both apply. Leap days, non-calendar fiscal
years and extreme `LocalDate` endpoints are supported. Sorting coverage costs O(n log n); the scan
and known-settlement checks are linear, not per-calendar-day work.

## Trust boundary and remaining work

This is **not yet connected to the application owner, preview, PDF, UI or any posting command**.
A caller can construct these plain data records. A `Snapshot` is consequently not proof of an
authorized review, an owner-bound capability, or persisted provenance. Empty findings mean only
that the supplied facts contain no history-policy conflict. They do not certify those facts, approve
posting, reserve a number, guarantee later write safety, or validate amounts/accounts/dimensions.

A later slice must establish persisted, company/year-bound reviewed provenance, explicit review
semantics and a safe correction path. No automatic or one-click certification has been added. Any
future writer must obtain authoritative history itself and enforce overlap protection atomically
with voucher creation, including concurrent writers and rollback. This pure assessment alone must
never be used as a reusable posting token. New-year creation must not silently manufacture reviewed
coverage either.

Existing report and settlement preview behavior is unchanged. Posting remains unavailable;
format-3 startup remains guarded and issue #75 remains open.

## Recorded evidence storage (V22)

V22 adds three initially empty tables: `vat_history_evidence`, `vat_history_coverage` and
`vat_history_known_settlement`. It adds no rows and changes no existing accounting tables. Header
records carry exact company/year SQL foreign keys, recorded fiscal bounds, nanosecond-capable UTC
instants, claimed reviewer text and an opaque evidence reference. Ordered child periods retain
separate coverage and known-settlement claims, including duplicates. Required attribution, date
shape, position and foreign-key constraints are enforced in SQL. Year/company deletion cascades to
its evidence and children; unrelated scopes survive. Evidence is not an independent archive.

`NormalizedVatHistoryReader.read` resolves explicit legacy selection identifiers to SQL BIGINT keys
without narrowing or conflating them. It requires the caller's serializable transaction and never
changes connection state, commits, rolls back or closes that connection. It reads only that exact
company/year and preserves each evidence record separately; it does not union different records
into a baseline or return an accepted policy `Snapshot`. Empty tables yield an empty evidence list,
not reviewed coverage. A missing V22 schema is a read failure, never empty history.

Changed/missing fiscal bounds, out-of-year periods, nonconsecutive child positions and missing/blank
attribution fail closed. SQL cannot enforce all cross-table date relationships; the reader checks
them before returning any history. Header order is database identity order, child order is recorded
position order; neither implies authority or acceptance. Reading costs two scope/header SELECTs
plus two child SELECTs per evidence record. Callers must keep the serializable read transaction
short and own its rollback/close lifecycle.

This storage records **claims**, not authenticated identities, reviewer permissions, immutable audit
proof, voucher links, accepted baselines or posting history. Attribution/reference text is not
verified evidence; records are not append-only or tamper-proof. There is deliberately no production
writer, review-acceptance command, automatic import/backfill, owner/UI/PDF/CLI integration, correction
workflow or baseline generation. Tests use explicit SQL fixture inserts, not an application review
shortcut. Existing migration machinery installs V22 only where it already installs catalogue
migrations; no new startup upgrade or legacy import path was added. Safe review/correction semantics,
source binding, authority and atomic overlap enforcement remain prerequisites for future posting.

## Verification

`VatSettlementHistoryTest` covers unknown history, full/partial/adjacent coverage, one-day and edge
gaps, inclusive overlap, independent blockers, duplicate facts, immutable lists, wrong scope,
invalid/null/out-of-year inputs, leap days, non-calendar fiscal years and date extremes. A fixed-seed
500-case comparison checks interval findings against an independent day-by-day coverage/overlap
oracle. Those pure policy tests require no database or graphical display.

`NormalizedVatHistoryReaderTest` verifies V21-to-V22 upgrade/idempotence without voucher inference,
scoped BIGINT/zero-legacy identities, exact attribution/time/order, independent evidence records,
SQL constraints, malformed and stale reads, transaction ownership, rollback, file-database reopen
and deletion isolation. The core schema contract explicitly includes V22 and its three tables.
