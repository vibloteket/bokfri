# Read-only VAT settlement account/dimension diagnostics

The guarded NOT POSTED settlement preview now includes **observed account/dimension
issues**, captured with the proposal in the same owner-held read. This narrows two
known posting gaps: the account-level planner does not carry source dimensions,
and the general VoucherValidator does not itself reject inactive accounts.
Neither planner arithmetic nor the generic validator is changed by this slice.

These are **non-exhaustive observations**, not a new posting gate, readiness flag,
posting token or approval. The proposal remains read-only and unnumbered. Even an
empty findings list explicitly says that posting approval is NOT granted and that
other validation, freshness, period policy and transaction safeguards remain
unperformed. No Post/Confirm action is added or enabled.

## Frozen findings

`VatSettlementDiagnostics.inspect` is a pure explicit-input observer. It reads the
fresh declaration report, selected account catalogue, hydrated source vouchers and
local unnumbered account-level proposal. It never calls ambient or mutating account,
project, unit or validity resolution, never reads SQL, and never repairs/assigns data.
The input proposal must be unnumbered and dated at the report's inclusive period end;
account catalogue numbers must be known and unique. This is not source certification.

Each immutable `Finding` contains a typed kind, account number, optional source voucher
number and **one-based row position**, and an optional raw dimension key. Proposal
account issues have no invented source references. The result list is immutable.

| Finding | Exact observation |
| --- | --- |
| INACTIVE_ACCOUNT | An account actually used by the proposed rows is currently inactive. |
| REQUIRED_PROJECT_MISSING | A proposed row lacks a required project; null/blank counts as absent. |
| REQUIRED_UNIT_MISSING | A proposed row lacks a required result unit; null/blank counts as absent. |
| SOURCE_PROJECT_NOT_CARRIED | An eligible tax-source row carries a non-null raw project key not represented by the account-level planner. |
| SOURCE_UNIT_NOT_CARRIED | An eligible tax-source row carries a non-null raw result-unit key not represented by that planner. |

Proposal-account observations are deduplicated by kind/account in proposal order.
Source observations retain input voucher/row order and exact raw keys, including
blank keys. No lookup, normalization, automatic assignment or allocation occurs.
Only accounts actually present in proposed rows are checked for activity/required
fields; an unused role is not a proposed row and does not receive such a finding.

Source inspection derives its tax codes from the same declared tax-field inventory,
including U1MI/U2MI/U3MI. It inspects inclusive-period, dated, non-crossed rows with
known tax accounts and at least one nonzero side. Unknown/null-number, crossed,
empty/zero, undated and out-of-period rows are excluded; tax-base-only rows are not
cleared and are not tax-source dimension observations. Added/inactive historical
source eligibility remains unchanged.

**Zero net is not proof that source dimensions disappeared safely.** Opposite amounts
on one VAT account can net to zero while different project/unit allocations remain.
Those raw source observations are retained even if the proposal has no rows. This is
a conservative account-level-planner limitation, not a dimension-group netting or
ownership/closure validation algorithm. Unknown/malformed source and catalogue
correctness, inactive-account posting policy, corrections and filing completeness
are not approved by these observations.

## Owner, display and PDF

`VatSettlementPreview.findings` is copied with the existing immutable rows/report/
scope/date/description/totals, inside the same SERIALIZABLE rollback-only read. No
mutable voucher/account/catalogue or posting capability escapes. Arithmetic and the
compatibility/declaration report choices remain unchanged. Query, period, owner and
uncertain-commit failures keep the existing fail-closed read lifecycle.

The preview header describes findings with literal account/source references and
quoted raw keys. An empty result is labelled **NOT posting approval**, never "ready
to post". Input/context changes and read failures clear the old result and export
capability; explicit Generate is the only refresh. Later metadata repair does not
rewrite an earlier frozen observation.

PDF export uses the exact displayed snapshot, including findings and NOT POSTED
warnings, without a new database read or diagnostic run. Existing new-file-only and
no-overwrite safeguards are unchanged. Net-zero source-dimension findings are still
exported on an empty proposal; an old PDF does not become a fresh validation result.
Original Latin-1 resource bytes are preserved; only new ASCII keys are appended.

## Proof and unfinished work

Eight pure tests cover flags/unused roles, optional and blank keys, zero-net distinct
dimensions, exact source eligibility, all tax-code groups, strict getters that reject
ambient resolution, immutable results and malformed explicit inputs. Four normalized
SQL tests cover same-read flags/no writes, zero-net stored dimensions with precise
source references, period/base/foreign isolation and frozen findings after metadata
repair. Four display tests cover concrete findings, real PDF with no SQL after a
later posting, the no-findings warning without readiness claims, explicit refresh
and failure cleanup. Existing preview/report/PDF/workspace contracts remain.

Posting still requires a complete, owner-controlled bookability/dimension/source
validation and confirmation protocol, duplicate/overlap and lock policy, numbering
and transaction/uncertain-commit handling. No posting UI/API, filing, CLI change,
schema migration, default cutover, guard removal, serialization retirement or
G2/G3/issue #75 technical completion is delivered here.
