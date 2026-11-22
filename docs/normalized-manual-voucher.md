# Guarded manual vouchers with explicit dimensions

The opt-in [normalized workspace](normalized-document-workspace.md) exposes a **Manual voucher**
tab after explicit company/year Apply. It creates new, balanced vouchers in that lease's
year. It is one restricted G2 workflow, not legacy voucher-frame parity or production cutover.

## Workflow and limits

1. Apply an explicit existing company/year. No date, description, account or amount is defaulted.
2. Load accounts and company-scoped projects/result units explicitly. Known-active accounts with
   known requirement flags are offered, with required dimensions labelled. Missing, inactive and
   foreign-year accounts are refused. No dimension is selected for you, even with one choice.
3. Enter the date, description and 2–200 ordered rows. Each row must have exactly one positive
   debit or credit amount, exact to cents. No rounding, automatic balancing or inferred VAT rows
   are performed. Amounts and the per-side total must fit the SQL monetary range. Choose each
   required project/result unit explicitly; optional dimensions stay None unless chosen. Keys
   are exact strings: `001` is not `1`. Foreign-company keys are refused. Closed projects are
   refused on and after their closure date, including concluded projects with an unknown date.
   An unconcluded project stays open even with a stored historical date.
4. Preview. The read-only summary identifies company, year and proposed voucher number; it shows
   date, literal description, current account and chosen dimension labels, ordered debit/credit
   rows and total.
   Any account presentation changed since loading is visible here, not silently retained.
5. Confirm once. The command rechecks exact SQL inputs in a serializable transaction and inserts
   one header and all rows atomically. A successful post clears the draft and leaves the active
   company/year unchanged. Existing document views remain owned by the same lease.

The number is one above the year's current maximum (at least 1), with checked overflow. The
company/year rows, complete account rows, selected-company project/result-unit rows (including
labels and closure metadata), and existing voucher IDs/numbers are snapshot inputs. Changed
scope, catalogue metadata or numbering requires a fresh preview. Unrelated companies do not
invalidate a local preview. Unrelated edits to old
voucher text/rows are not inputs to a new voucher and are never overwritten by this command.

Edits invalidate confirmation, **including entering a JTable cell editor before its value has
committed to the table model**. Accounts/amounts are validated again on preview and posting.
Dimension edits also invalidate confirmation at editor start. Rows cannot carry correction
links, crossed/added flags or edit signatures. No project/result-unit catalogue data is created
or edited by this workflow.
No account defaults, dates, currency conversion, carry-forward or legacy numbering calls are used.

## Ownership, validation and failures

`Selection.vouchers()` returns `NormalizedManualVoucherCommands`, owned by the same selection as
both document sessions. All three share the owner's transaction gate and schema-validated,
commit-observing connection factory. No long-lived connection is held. A token belongs to exactly
one command context; foreign/replaced/closed/blocked contexts refuse it before opening SQL.

The shared `VoucherValidator` now has an explicit accounts/projects/result-unit catalogue overload.
This overload rejects missing catalogues and never uses ambient legacy row-resolution getters.
The existing two-argument legacy API remains available with its original context behavior.
Creation passes explicit year accounts and company-scoped dimension catalogues, after its
stricter exact-cent/balance, account-eligibility and project-closure checks. Pure number/date and five-argument row constructors
avoid legacy constructor defaults. Stored account references reopen through the normalized reader.

A failure after the second row insert rolls back the header and every row; the token is consumed
and a fresh preview can retry. Any failed commit attempt, including an unchecked failure and either
durability outcome, blocks manual/document/company/year commands until external reconciliation and
a new owner. A confirmed commit followed by a connection-close failure returns success with a warning,
not a retryable result. Accepted writes finish on the original year after owner closure.

The panel uses background JDBC and EDT-only UI. Pending commands disable host context reload and
company/year/manual inputs through state-only interlocks; no extra JDBC polling is introduced.
Closing fences callbacks without cancelling accepted transactions. The owner remains responsible
for the command lease; the panel does not shut down the database.

## Verification

- `NormalizedApplicationManualVoucherTest`: read-only choices/preview, exact balanced SQL/reopen
  state and every pre-existing row preserved, other-year isolation, invalid amounts/dates/accounts/
  dimensions, extreme decimal exponents and total range, stale scope/catalogue/numbering, foreign/
  replaced/closed contexts, second-row rollback/retry, unknown commit on both durability sides,
  known-commit close failure, accepted completion after closure and numbering overflow. Legacy
  JDBC is blocked during creation; a strict row subclass fails ambient validator getters.
- `NormalizedApplicationManualVoucherGuiTest`: explicit rows/preview/post and draft clearing,
  cell-editor invalidation, current preview account labels and stale refusal, replacement,
  pending-write interlock, uncertainty, closure and malformed input/row add/remove handling.
- `NormalizedApplicationVoucherDimensionsTest`: read-only catalogue/preview, exact dimension
  keys and company isolation, missing/optional requirements, inclusive and unknown closure
  dates, stale dimension labels/deletion, rollback/retry, uncertain/known commits and retirement.
- `NormalizedApplicationVoucherDimensionsGuiTest`: explicit dimension choices and preview labels,
  no defaults, optional nulls, closure dates, stale metadata, replacement, pending/uncertain
  commands and dimension editor-start invalidation.
- Existing `VoucherValidatorTest` preserves legacy validation behavior. All application test
  classes match the existing `NormalizedApplication*Test` GUI CI selectors; GUI evidence requires
  Xvfb/a real display and `-Dawt.headless=false`, not headless skips.

Dimension catalogue editing, edits/corrections/deletion, imports/templates/numbering-series parity,
full legacy voucher-frame integration and end-to-end accounting gates remain separate. Production
format-3 startup stays rejected; new databases remain format 2. See the
[cutover checklist](normalized-cutover-checklist.md); G1–G4/#75 remain technically unfinished.
