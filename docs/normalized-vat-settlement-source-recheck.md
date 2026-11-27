# Guarded VAT settlement source recheck

The guarded Financial reports tab now exposes an explicit **Recheck sources
(read-only)** action for a successfully generated NOT POSTED settlement preview.
It consumes the existing backend owner-bound source review; it is not a posting,
confirmation or number-reservation workflow.

## Explicit capture and check

1. Explicitly choose the field-total VAT settlement preview and period; Generate.
2. Generation captures the immutable proposal **and** private source state in one
   existing owner-held SERIALIZABLE rollback-only read. No check runs automatically.
3. Recheck sources performs another explicit rollback-only read off the EDT.
4. The status reports either current state matches, or current state differs.

The control is disabled until a successful settlement generation, for all other
report choices, while busy, or while the host/owner/selection is unavailable or
blocked. It never checks in the background or polls storage.

A match is explicitly **NOT posting approval, transaction history, a lock or a
guarantee for a later write**. Exact restoration of source values may match again.
A difference does not refresh or repair the old proposal. Generate again is the
only way to obtain a new displayed proposal and source review. The conservative
nine-table scope, issuer binding, value-equality limitations and remaining gates
are documented in [source review](normalized-vat-settlement-source-review.md).

## Frozen display and PDF

Successful checks change **status only**. The original table, header, immutable PDF
snapshot and source review remain unchanged, including after a CHANGED result.
Repeated checks are permitted. The old PDF remains exportable as an explicitly
frozen NOT POSTED proposal, not a current validation result.

The generated header and PDF explain that source state was captured, that mutable
recheck outcomes are **not included in that frozen PDF**, and that neither capture
nor a source match approves posting. Export uses the same exact snapshot object
before/after checks and performs no SQL or diagnostic/source re-read. No freshness
badge, check timestamp or successful-check certification is silently added to an
old PDF.

## Invalidation and failures

Period, account or report-kind edits, selection replacement and panel closure clear
the review with the old result/export capability. Failed source reads—including
query, rollback and connection-close failures—clear the old result and require a
new explicit Generate. Checks do not create commit uncertainty. Existing uncertainty
from a real posting disables checks, generation and export before further SQL.

The shared worker retains request/input revisions and owner/host interlocks. Pending
checks disable actions; results arriving after input edits, replacement or closure,
or while the host is unavailable, cannot restore an old review or export capability. Ordinary
report generation continues to replace its result; checks alone preserve it on
accepted success.

## Proof and boundaries

Twelve real-display regressions cover explicit-only activation, unchanged and changed
state (including edits with an equal displayed proposal), state restoration, exact
PDF snapshot identity/no SQL on export, no check outcome in PDFs, explicit refresh,
input/kind/selection invalidation, query/rollback/close errors, busy/input races,
pending replacement/closure, host interlocks and uncertain posting fences. Existing
VAT report, policy, diagnostics, source review and snapshot contracts remain.

Report-kind indices/defaults, compatibility/declaration amounts, exact clearing/
control/rounding, source comparison semantics, CLI, schema and startup guard are
unchanged. No posting UI/API, confirmation token, number reservation, complete source
certification, period/duplicate/lock approval, default cutover, guard removal or
G2/G3/issue #75 completion is delivered.

## Frozen proposed row/amount observations

The settlement preview also captures [limited exact row/amount findings](normalized-vat-settlement-amount-diagnostics.md)
in the same read, without new SQL or amount changes. Proposed-row and total positions
are distinct from source references; fractional cents and storage/manual-entry
range observations do not round, repair or authorize posting. Source checks remain
status-only; old warnings/PDF stay frozen/exported without SQL. Empty findings, a
source match and balance do not approve posting or the remaining gates.
