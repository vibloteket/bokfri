# Guarded read-only voucher register

After explicit company/year Apply, the opt-in normalized workspace exposes a fourth **Voucher
register** tab. This is a read-only projection of stored journals, not the legacy voucher frame,
a correction editor, an audit/balance certification or production format-3 startup.

## Workflow

1. Apply the company/year explicitly. The register has no automatic reads or selection.
2. **Refresh first page** reads up to 100 headers in descending voucher-number order. An explicit
   **Next older page** action uses the last displayed number as an exclusive keyset cursor;
   the UI reports when more headers exist. Refresh first page resets the cursor. Pages are
   independent read snapshots, not a frozen export: changes between reads can alter results.
3. Select a header explicitly, then **Open selected voucher**. The SQL identity, not its display
   number, selects the journal, and the backend verifies that it belongs to the leased year.
4. Inspect the raw header and ordered rows. Opening rereads the header, correction references,
   rows and current catalogue labels in one serializable read transaction. Selecting another
   header, refreshing, replacing the context, or closing clears the previous detail. Failed
   reads do not leave a prior successful detail displayed as though it were current.

The register never writes SQL or commits a transaction. Schema validation and read transactions
are rolled back and connections closed. Manual posting does not silently reload or select a
voucher here: use Refresh explicitly, including after reopening the workspace/database.

## Stored values versus current labels

- SQL header identity, number, nullable date/description, correction SQL identities and their
  scoped display numbers are shown. Correction links are not traversed or reconstructed;
  a malformed historical graph is not silently repaired.
- Rows retain their stored order numbers (including gaps), nullable account references, raw
  debit/credit decimals without cent rounding, exact project/result-unit string keys, edit
  timestamps/signatures, and crossed/added flags. Zero, null and empty strings stay distinct.
- Account descriptions and project/result-unit names are **current catalogue metadata**, not
  historical labels. Dimension existence is shown separately, so a missing dimension and an
  existing dimension with a null name are not conflated. Joins use the selected year/company;
  an identically numbered dimension in another company cannot supply its label.
- The noneditable table includes crossed rows rather than hiding them. No totals, active-row
  interpretation, balance assertion or inferred values are presented. Nulls appear as `null`,
  strings are quoted/escaped, and HTML-like text stays literal rather than Swing-rendered HTML.

The projection reads journals directly, including historical shapes that the new-manual-voucher
writer intentionally refuses to create. It neither calls legacy row/model constructors nor
consults ambient legacy selection or serialized domain payloads.

## Ownership and failure boundaries

`Selection.voucherRegister()` returns a lease-owned `NormalizedVoucherRegister`. It shares the
application transaction gate and schema-validated connection factory with manual, customer and
supplier commands. Company/year ownership and the leased year dates are revalidated per read.
Foreign/missing voucher identities, changed year scope, and retired/closed/blocked leases fail
closed; retired/blocked readers refuse before opening a connection.

The panel runs JDBC off the EDT and fences callbacks by revision/closure. A pending read
interlocks host Reload/Apply and company/year/manual commands without polling SQL. Context
replacement waits for the shared gate; closing the owner can retire a pending read without
returning its payload. Closing the panel fences callbacks and clears data without owning the
database lifetime. Unknown commits elsewhere retire this view and clear its displayed data.
Read/query/rollback/close failures are errors, not uncertain commits, because no commit is tried.

## Verification and remaining work

`NormalizedApplicationVoucherRegisterTest` covers read-only SQL and transaction observations,
exact raw values and unchanged full database snapshots, pagination/negative numbers, correction
references/null headers, foreign SQL identities, current rereads, dimensioned manual posting and
reopening, stale scopes, retirement, commit uncertainty, read failures and shared-gate/closure
races. `NormalizedApplicationVoucherRegisterGuiTest` covers explicit selection, immutable tables,
raw/current-label display, page resets, context replacement, error cleanup/recovery, pending-read
interlocks, callback fencing and uncertainty retirement. Existing workspace tests assert all
four tabs. GUI verification requires Xvfb/a real display; headless skips are not display proof.

Edits/corrections/deletion, dimension catalogue editing, legacy voucher-frame parity, report
certification, production integration and complete G1–G4/#75 technical completion remain separate.
Production format-3 startup stays guarded and new databases remain format 2. See the
[cutover checklist](normalized-cutover-checklist.md).
