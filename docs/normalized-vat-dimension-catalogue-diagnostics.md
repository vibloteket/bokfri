# Read-only VAT dimension catalogue observations

The guarded NOT POSTED settlement preview now observes **proposed** raw project and
result-unit keys against the selected company's current catalogues. Faithfully
carrying a key does not establish that it belongs to that company or that its
project remains open at the proposal date.

These are non-exhaustive observations, not complete bookability, historical source
validation or authorization to post. Empty findings still explicitly do NOT approve
posting. There is no repair, fallback, inferred allocation, new posting action,
number assignment or confirmation token. The startup guard stays enabled.

## Exact observations

Existing activity, required-dimension and exact source-group reversal observations
remain unchanged and precede the new observations. New findings are deduplicated by
kind/account/raw key in proposed-row order, without invented source voucher/row
references. Only actual proposal rows are observed: cancelled groups with no row
are not certified as historical sources.

| Kind | Observation |
| --- | --- |
| PROJECT_KEY_BLANK | A non-null raw project key is blank, even when optional. |
| UNIT_KEY_BLANK | A non-null raw result-unit key is blank, even when optional. |
| UNKNOWN_PROJECT | Exact raw project key is absent from selected company. |
| UNKNOWN_UNIT | Exact raw result-unit key is absent from selected company. |
| PROJECT_CLOSED_ON_DATE | Project is concluded and closure date is on/before proposal date. |
| PROJECT_CLOSURE_DATE_UNKNOWN | Project is concluded but its closure date is absent. |

Null optional keys require no catalogue identity; existing required-field findings
still cover missing required values. No trim, global search, legacy lookup, project
or unit resolver is invoked. A key present only in another company remains unknown;
no other company's identity or status is exposed.

Closure uses the proposed settlement date (inclusive period end), not each source
voucher date. A future closure date is not observed as closed. If the concluded flag
is false, an old stored closure date does not make the project closed. This matches
the date boundary in manual-voucher validation; it is not approval of all posting
policy. Result-unit models have no closure flag, so no unit-closure finding is
invented.

## One frozen rollback-only read

`VatSettlementDimensionDiagnostics` receives an explicit unnumbered dated proposal
and immutable scalar catalogue. Project records contain only raw identity,
concluded flag and closure date; units are copied raw strings. Duplicate, null or
blank catalogue identities fail closed rather than selecting an arbitrary match.
No mutable catalogue model escapes.

The owner loads both catalogues with selected-company queries on the existing
SERIALIZABLE rollback-only connection **only for settlement preview**. Other report
choices do not add catalogue queries. Existing selection, year, uncertainty, close
and read-failure fences remain in force. There is no commit or persistence callback.

Findings are copied into the same immutable preview; neither rows nor amounts are
changed. The existing literal raw-key header rendering and displayed-snapshot PDF
include all new finding labels. Export does not reopen SQL or rerun observations;
repairing a catalogue or posting later does not rewrite the old display/PDF. An
explicit Generate is required to refresh. A failed read clears old results and
export capability.

## Proof and boundaries

Ten pure tests cover optional/null, known/unknown/blank/raw keys, closure before/on/
after proposal date, unknown closure date, open flags, explicit company catalogues,
deduplication/source-free references, immutability, malformed catalogues/proposals
and strict rows that reject ambient or mutating resolution. Four SQL tests cover
selected-company scope including foreign-only keys, raw blank preservation, date
boundary, same-read/no-write lifecycle, immutable old findings after repair and
malformed-catalogue failure isolated from other report choices. Four GUI/PDF tests
cover concrete new labels/raw keys, no SQL during frozen export after repair and
later posting, explicit refresh/no approval and failure cleanup/retry.

The grouped planner, exact rows/control/rounding, compatibility plans/reports,
schema, CLI and default startup are unchanged. Complete source correctness,
bookability, duplicate/overlap/lock policy, owner confirmation, numbering and atomic/
uncertain-commit safeguards remain unfinished. No guard removal, serialization
retirement or G2/G3/issue #75 technical completion is delivered.
