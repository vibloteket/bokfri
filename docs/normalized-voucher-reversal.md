# Guarded standalone voucher reversal

The opt-in normalized workspace now has a separate **Voucher reversal** tab. It creates a new
journal with every eligible original row's debit and credit swapped, preserving account and
exact project/result-unit keys and relative row order. It then links the two journal headers
atomically. It never overwrites/deletes the original rows or unbooks/changes a document.
This is one bounded correction operation, not a general voucher editor or legacy-frame parity.

## Explicit workflow

1. Apply the company/year explicitly and inspect the source in the read-only Voucher register.
2. Enter that source's **SQL identity**, not its voucher number, in Voucher reversal. No source,
   date, description, account or dimension is silently selected. Enter the new date and
   description explicitly; the date must be in the leased year and on/after the original date.
3. Preview. The summary shows company/year, original identity/number/date/literal description,
   original rows, proposed reversal number/date/description, swapped rows, current account and
   dimension labels, and the reversal's equal debit/credit total. Raw strings are quoted/escaped.
4. Confirm once. Editing any input invalidates confirmation. Posting inserts the new header and
   ordered rows, sets its `corrects_voucher_id`, and sets the original's `corrected_by_voucher_id`
   in one serializable transaction. The only change to the original is that narrow header link.
5. Refresh/Open explicitly in the read-only register to inspect both links and rows. Posting
   clears the reversal draft, without changing active company/year or auto-refreshing a register.

## Eligibility and conservative reference guards

Only a source in the selected year with a known date, no existing correction links, and no
inbound correction references can be reversed here. Correction chains, already-reversed sources,
one-sided historical correction graphs, and foreign/missing SQL identities are refused.
Rows must have known accounts, no crossed/added flags, and no edit timestamp/signature (including
an empty stored signature). The swapped draft must pass the existing strict manual-voucher
validation: 2–200 rows, one positive exact-cent side each, exact balance/storage range, active
accounts with known requirement flags, required company-scoped dimensions and usable projects.
There are no balancing/rounding/VAT rows, inferred dimensions or historical-row repairs. Inactive
accounts, missing dimensions and projects closed by the reversal date require separate handling.

Customer invoice/credit, supplier invoice/credit, inpayment/outpayment and periodic-invoice
voucher snapshots are checked for the source number in the selected company. These historical
snapshots do not reliably identify an accounting year, so **any same-company matching number
blocks**, even if from another year or an unbooked/ambiguous snapshot. This deliberately prefers
refusal over guessing provenance. Foreign-company snapshots do not block or redirect a source.
Templates and generated periodic snapshots are included. This is not document unbooking; use
separate document-specific reconciliation for linked/ambiguous journals.

## Ownership, staleness and failures

`Selection.vouchers()` owns `previewReversal`/`postReversal` alongside plain manual commands.
The opaque reversal token is command-bound and single-use; its underlying new-voucher preview
is not exposed for accidental plain posting. Preview is read-only and captures the complete raw
source header/ordered rows in addition to current company/year/account/dimension and numbering
inputs. Posting compares those snapshots, rechecks correction/document references and source
eligibility, and uses the shared owner gate/schema-validated commit-observing connection factory.
Unrelated old-journal text edits are neither inputs nor overwritten. A new reference, changed
source row/header/row order, catalogue or numbering input requires a fresh preview.

Partial row insertion and a failure after the final original-header link roll back the whole
operation, consuming the token but leaving the source and all other data unchanged. A confirmed
commit followed by close failure reports success, not a retryable result. Any failed commit
attempt, even with uncertain durability or an unchecked error, blocks all owner command families
until external reconciliation and a new owner. Do not retry an uncertain reversal.

The panel uses background JDBC and EDT-only UI. Pending commands interlock host Reload/Apply,
company/year/manual/reversal inputs and register reads without polling SQL. Closing fences UI
callbacks but lets accepted transactions finish on the original lease; context replacement
retires old factories/tokens. The original register remains a read-only fourth tab.

## Verification and remaining scope

`NormalizedApplicationVoucherReversalTest` covers exact side/dimension reversal and reciprocal
links/reopen, original-row and unrelated-SQL preservation, explicit dates/inputs and unsupported
history, correction graphs, stale snapshots/references, all seven document snapshot families
(including a schema-inventory audit), company isolation, late row/final-link rollback/retry,
uncertain/known commits and command ownership. `NormalizedApplicationVoucherReversalGuiTest`
covers preview/post/reset, literal current labels, input invalidation, stale/linked/foreign
sources, rollback/uncertainty, pending-command interlocks and accepted completion after closure.
GUI proof requires Xvfb/a real display; headless skips alone are not sufficient.

General row editing/corrections, correction chains, document-linked reversals/unbooking,
dimension catalogue editing, imports/templates and full legacy voucher-frame/accounting parity
remain separate. Production format-3 startup stays guarded, new databases remain format 2, and
G1–G4/#75 technical completion is not claimed. See the [cutover checklist](normalized-cutover-checklist.md).
