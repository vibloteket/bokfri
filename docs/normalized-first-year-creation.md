# Guarded first-year creation for an existing company

The opt-in [normalized workspace](normalized-document-workspace.md) supports creating the
first **empty** accounting year for an existing company whose year catalogue is empty.
This closes the no-prior-year prerequisite of the earlier
[year-creation command](normalized-year-creation.md). It does not create a company,
change production bootstrap or complete G2/#75.

## Explicit workflow

1. Choose the existing company in the workspace company selector. If it has no years,
   Apply remains disabled, but the year-creation panel names that first-year target.
   The active document lease, if any, remains unchanged and may belong to another company.
2. Load templates explicitly, select one by its numeric identity and enter both ISO dates.
   No dates or account plan are defaulted. Duplicate template names remain distinct.
3. Preview and confirm. The summary identifies the target company, inclusive dates and
   chosen account plan, and explicitly describes an empty independent year with no
   carry-forward or document-context change.
4. Confirmation atomically creates the first year and its account-plan copy. It refuses
   a company that acquired any year since preview; it never turns a first-year preview
   into an additional-year command.
5. The workspace refreshes choices for that company, leaving the year placeholder selected.
   No document views are installed and no saved selection is changed automatically.
   Explicitly choose the new year and Apply to open it. The usual selected-year command
   can then create subsequent years.

Changing the draft company clears dates/template/preview. Choosing a yearful company that
is not the active document company disables year creation until explicit Apply. These are
draft/UI changes, not implicit replacement of an existing document lease.

Catalogue/selection loads disable year-command input. Pending year commands disable the host
company/year choices, Apply and Reload until their callback completes; this prevents a stale
reload from hiding a successful first-year creation. This is an EDT state-only interlock,
not another JDBC poll or cancellation of an accepted transaction. Closing remains possible.

## Backend boundary

- `NormalizedApplicationOwner.firstYearAccountPlans(companyId)` and
  `previewFirstYear(companyId, from, to, planId)` require an explicit existing company
  with **zero** years. They require no current document lease and do not change one.
- The opaque preview captures the company identity and exact SQL inputs. It is bound to
  its originating owner; another owner cannot confirm or consume it. A first-year token
  is deliberately independent of later document selections, retaining its explicit target.
- The existing `createYear(preview)` shares the document writer gate, serializable SQL
  revalidation, global year-ID allocation, full rollback, single-use confirmation and
  uncertain-commit boundary. No global/default IDs, legacy JDBC, seeding, migration,
  vouchers, opening balances or budgets are used to invent a first year.
- Empty global year catalogues allocate their first ID normally. A company-only preview
  rejects missing/deleted/renamed companies, changed templates and concurrent first-year
  creation rather than refreshing accepted inputs silently.
- Unknown commits block year, catalogue, selection and both document families until
  external reconciliation and a new owner. Confirmed commits followed by close failure
  still return success. Accepted writes keep their original target after owner closure;
  closed/replaced UI callbacks cannot activate the new year.

## Verification

`NormalizedApplicationFirstYearTest` covers no-lease creation with every pre-existing SQL
row preserved and legacy JDBC blocked, empty global IDs, empty account balances/documents,
explicit target independent of document selection, invalid/missing/nonempty company inputs,
stale SQL/concurrent creation, foreign/closed owners, rollback/retry, known-commit close
failure, uncertain commits before/after durability and accepted-write completion after close.

`NormalizedApplicationFirstYearGuiTest` covers the complete choose/preview/confirm/refresh/
explicit-Apply workflow, unchanged existing document context/views, draft invalidation,
concurrent creation refusal, pending-write/reload interlocking, both uncertainty outcomes and
closure during accepted writes.
Both classes match existing `NormalizedApplication*Test` CI selectors; GUI evidence requires
Xvfb/a real display and `-Dawt.headless=false`, not headless skips.

Production format-3 startup remains rejected, and new databases still default to format 2.
Company creation, production opening, broader commands, carry-forward, backup/restore,
parity gates and serialization retirement remain in the
[cutover checklist](normalized-cutover-checklist.md).
