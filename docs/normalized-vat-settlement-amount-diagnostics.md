# Read-only VAT settlement row/amount observations

The guarded NOT POSTED settlement preview now freezes exact **proposed-row and
amount** findings alongside its existing account/dimension observations. A balanced
proposal can still contain fractional cents or values outside existing entry/storage
bounds. The observer reports those facts without rounding, repair or posting.

These are limited observations, not complete bookability, historical source
certification or a new authorization policy. Empty findings explicitly do **not**
approve posting. The startup guard and remaining posting gates stay in place.

## Exact findings and positions

`VatSettlementAmounts.inspect` is a pure explicit-input observer. It uses only raw
account/debit/credit getters, never account/project/unit resolution, SQL, a writer,
number assignment or mutable validity getters.

Each immutable finding contains a kind, optional one-based **proposed row** position,
optional raw account, optional debit/credit side and the exact original BigDecimal.
These are not source voucher/row references. Row-shape findings have no invented
side/value; total findings have no invented row/account. Output order is proposed
row order (shape, debit, credit), then debit/credit total observations.

| Kind | Exact observation |
| --- | --- |
| MISSING_AMOUNT_SIDE | Both proposed amount sides are absent. |
| BOTH_AMOUNT_SIDES | Both proposed amount sides are present. |
| NON_POSITIVE_AMOUNT | A present side is zero or negative. |
| FRACTIONAL_CENT | A present side cannot be represented exactly at scale 2. |
| STORAGE_RANGE | A side cannot fit exact SQL DECIMAL(100,30): more than 70 integer digits or a nonzero fraction beyond scale 30. |
| TOTAL_ENTRY_RANGE | Exact proposed debit/credit total exceeds the existing manual-entry 70-integer-digit bound. |

Trailing zeros are not fractional cents. Scale checks use UNNECESSARY, not rounding
or truncation; original values and scales remain untouched. Zero is observed as
non-positive, not spuriously out of range because of its scale. No approximate or
rounded balance test is introduced; the owner's existing exact-balance/date/number
fences remain unchanged.

The side range comes from the normalized voucher-row storage type. The **total**
bound is the existing manual-voucher entry constraint, not a SQL voucher-total
column restriction or an approved future VAT posting policy. Distinct dimension
groups can each fit storage yet exceed that aggregate entry bound. Empty proposals
need no invented zero rows and are not certified as postable vouchers.

## Frozen read, display and PDF

The owner copies `VatSettlementPreview.amountFindings` with List.copyOf in the same
existing rollback-only read. Plain previews and source-review issuance share this
capture helper. No additional SQL is introduced; the planner's exact clearing,
control, rounding, grouping and declared report amounts are unchanged.

The header/PDF distinguishes proposed rows from totals, shows side/account/exact
amounts where known, and labels all findings read-only/NOT posting approval. A fresh
read can observe repaired source inputs; it never rewrites an older preview.

Source rechecks still change status only. Even a matching source state does not
approve a displayed amount finding. Export still reuses the exact old frozen PDF
snapshot without SQL or re-running these observations, including after later source
repair and a CHANGED recheck. Mutable check outcomes remain excluded from the PDF.
Read failures clear old warnings/results/review/export through the existing lifecycle.

## Proof and boundaries

Ten pure tests cover side presence, positivity, exact cent/storage boundaries,
trailing zeros/exponents, separate row/aggregate bounds, raw getters, immutability
and malformed inputs. Four SQL tests cover preserved fractional-cent source values,
grouped clearing/control overflow from individually valid stored amounts, aggregate
entry overflow without side-storage overflow, same-read/no-write/company isolation,
and frozen old findings after source repair. Four GUI/PDF tests cover scoped labels,
exact old warnings/values exported without SQL after repair/recheck, total-vs-row
wording, limited no-findings warnings and failure cleanup/explicit regeneration.
Existing source review/recheck/report/policy/PDF contracts remain.

No arithmetic repair, cent rounding/allocation, new posting action/API/token/number
reservation, schema/CLI/default/guard change or G2/G3/issue #75 completion is delivered.
Complete source correctness, period/duplicate/lock policy, full bookability and atomic
confirmation/numbering/uncertain-commit handling remain unfinished.
