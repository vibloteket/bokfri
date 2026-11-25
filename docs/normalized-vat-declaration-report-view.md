# Guarded read-only VAT field-total policy report

The opt-in normalized Financial reports tab now offers two **explicit** VAT choices:

- **VAT report (compatibility: per-account truncation)** retains the previous report
  amounts and eligibility. Its label makes the compatibility policy explicit.
- **VAT report (field-total whole-krona policy)** selects the separately named
  [declaration policy](explicit-vat-declaration-policy.md). Exact account balances
  are added within each field before truncation toward zero; box 49 is derived
  from the declared output-tax fields minus field 48.

Neither choice is selected automatically. Choose the company/year, report kind and
inclusive From/To dates, then Generate. There is no inferred month/quarter, automatic
refresh, account selection, conversion or inferred settlement role. Both display
all 16 supported fields, including zero, their current VAT-code groups and the
signed payable/refundable total. This remains a **supported subset, not a complete
or certified declaration**, filing operation or settlement preview.

## Read ownership and policy isolation

`NormalizedFinancialReports.vatDeclarationReport(from,to)` uses the existing
selection/lease-owned SERIALIZABLE read boundary, explicit hydration, rollback-only
completion and callback fences. It invokes only `VatService.declarationReport`,
never the planner or writer. Null dates fail before opening storage; ordered dates
inside the selected year's current bounds are required. Closed/replaced/blocked
selection, changed year bounds and uncertain prior commits fail closed.

The existing `vatReport` method remains the compatibility path. Both share period
validation, source loading and scope capture. Current mappings are used on each
explicit Generate. Opening amounts are excluded and all prior raw-row eligibility
rules remain. No SQL mutation or commit is introduced.

The view's title and explanatory header identify the chosen policy. Input/kind
changes clear old rows, header and exportable snapshot. Input revision is also
captured when a background read starts: if inputs/policy change before it completes,
its result cannot be displayed/exported under the new title. The old read still
finishes/rolls back; the user must explicitly Generate again. Context replacement,
closure, host interlocks and shared uncertain-commit blocking remain in force.

## Displayed-snapshot PDF

The existing immutable snapshot exporter receives the **displayed** title, policy
meaning, scope, period, column labels and exact displayed cell strings. It does not
re-read SQL, recalculate VAT or substitute a later report. A subsequent posting or
mapping change does not change the earlier exportable snapshot; Generate is explicit.
Switching policies invalidates that snapshot rather than relabelling it.

Explicit destinations and existing no-overwrite/hard-link publication rules remain;
no PDF engine, template, file-safety implementation or CLI PDF behavior changes.
Legacy template parity is not claimed. The compatibility view's title is now more
specific, but its accounting values are unchanged.

## Proof and remaining gates

Eight backend tests prove differing split-account totals, immutable scoped reads,
compatibility preservation, inclusive periods/reversals, mapping refresh, empty and
foreign contexts, validation, rollback/query/close failures, uncertain commits,
year-bound checks and closed-owner fences.

Nine display tests prove explicit separate choices, distinct policy meaning, exact
field values, no-write display, real PDF text, no-SQL snapshot export after another
posting, mapping/reversal refresh, input/context invalidation, failures, interlocks,
owner uncertainty and pending-read policy-change fencing.

The counterexample fixture has two output accounts at 0.99 each: compatibility
reports zero, field-total policy reports one. Posting it a second time yields an
exact field total of 3.96 and a freshly generated result of **3**, not the sum of two
previously rounded report values. The old displayed/PDF snapshot remains **1**.

Follow-up: a [separate read-only settlement preview](normalized-vat-settlement-preview.md)
now shows frozen clearing/control/rounding rows with explicit NOT POSTED warnings.
It does not change these two report choices or expose a posting token/action.

This declaration-report choice adds no settlement control, plan, posting, period lock, duplicate-period protection,
stale-source posting token, filing or default startup activation is added. Normal
format-3 startup remains guarded and new databases still default to format 2. This
read-only slice does not complete G2/G3, serialization retirement or issue #75.
