# Shared normalized application owner

`org.fribok.bookkeeping.application.NormalizedApplicationOwner` is an explicit, opt-in
ownership boundary for the restricted customer and supplier document screens. It is a G2
building block, **not production bootstrap or completion of the application cutover**.
Normal GUI startup still rejects format 3, and new databases still use format 2.

## Ownership and selection

- Construction is lazy. The caller provides a fresh, exclusively owned auto-commit JDBC
  connection factory and clock. The owner never holds a long-lived connection and never shuts
  down a database supplied by its caller.
- `companies()` returns immutable company/year identities and labels from one gated,
  read-only transaction. It does not change persisted GUI selection or legacy singleton state.
- `select(companyId, yearId)` validates current company/year membership before publishing a
  new lease. An invalid selection leaves the previous lease usable. Company/year IDs are the
  existing migration identities; document actions still use stable SQL document IDs.
- A selection exposes `customers()` and `suppliers()` sessions sharing the same writer gate,
  connection factory, company/year context and application-wide uncertain-commit boundary.
  The existing domain services continue to own their action-local transaction/savepoint logic.
- Replacement retires both old session factories after accepted transactions release the
  shared gate. Closing the owner invalidates future work immediately without waiting for an
  accepted write or closing its connection. Accepted writes can finish on their original
  identities, never on the new selection.

All database methods must be called off the EDT. The companion
[explicit document workspace](normalized-document-workspace.md) now hosts catalogue selection,
both views and their owner-scoped menu lifecycle in a dedicated opt-in window. It replaces both
sessions after successful background selection and fences late callbacks; it also observes the
shared uncertainty state and retires both screens. A custom host can still construct the
existing `CustomerDocumentView` and `SupplierDocumentView` from the same selection, but must
coordinate these responsibilities explicitly. The owner itself does not automatically install
menu bindings, dispose windows, emit global cache notifications or call legacy startup.

The owner also supports restricted [empty-year creation](normalized-year-creation.md) via
`accountPlans(selection)`, `previewYear(selection, from, to, planId)` and `createYear(preview)`.
These commands share the writer gate and uncertain-commit boundary; they do not replace the
active lease or persist selections. For an explicit existing company with zero years,
`firstYearAccountPlans(companyId)` and `previewFirstYear(companyId, from, to, planId)` provide
an owner-bound company-only command without a prior document lease. Confirmation still uses
`createYear(preview)`, rejects a concurrently added year, and leaves document context unchanged.
See [first-year creation](normalized-first-year-creation.md).

The owner also exposes [guarded empty-company creation](normalized-company-creation.md)
through `companyCurrencies()`, `previewCompany(CompanyDraft)` and
`createCompany(CompanyPreview)`. This explicit owner-bound command needs no document lease,
shares the transaction/uncertainty boundary, and creates no year or accounting defaults.
Existing document context and saved selections remain unchanged.

## Validation and uncertainty

Every dedicated connection is checked for format 3 and the **complete current packaged
schema history**, including checksums, before it is leased to an action. The validation is
read-only and rolls back before returning a fresh auto-commit session to existing adapters.
Legacy format, missing migrations and tampered history fail closed; no migration, default
seeding, repair, format initialization or saved-selection write is attempted.

A guarded connection observes commit failures from either document family. Any failed
commit attempt, including an unchecked failure and either durability outcome, blocks both
families, catalogue reads and selection changes **before another connection is opened**.
Neither reselecting nor closing a screen clears this owner-level block. The host must reconcile
the database externally before deliberately creating a new owner. `isBlocked()` exposes the
state for host UI policy. The explicit document workspace polls this state without JDBC and
retires both screens on uncertainty; custom hosts must provide their own equivalent policy.

Ordinary pre-commit validation/write failures do not poison the owner. A successful commit
followed by a connection-close failure remains a known success under the existing adapters;
it does not block the other family or justify repeating the write. Driver-specific connection
unwrapping is not exposed through leased connections.

## Evidence and remaining work

`NormalizedApplicationOwnerTest` exercises immutable read-only choices, scoped selection,
replacement, invalid selections, both actual document session families, rollback behavior,
full-history validation on every lease, uncertain commits from either family on both durability
sides, unchecked commit failure, confirmed-close failure, close during an accepted write and
selection replacement waiting for the original transaction. Snapshot comparisons and a legacy
JDBC guard verify that ownership and reads do not mutate stored selection or use legacy SQL.

`NormalizedApplicationOwnerGuiTest` loads both real document views from one owner, replaces
both with another company/year, and verifies that an uncertain write prevents backend refresh
from opening connections in either screen. These require a real display/Xvfb; headless skips
are not display-validation evidence.

Still separate: production host/controller and menu installation, automatic current-context
notifications, command ownership for company/year/register/voucher and remaining document
workflows, cross-domain end-to-end parity, GUI backup/restore, default format-3 startup and
legacy serialization retirement. G1–G4 remain unfinished; this boundary must not be used to
justify disabling the production format guard.
