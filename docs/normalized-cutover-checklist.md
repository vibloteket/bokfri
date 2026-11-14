# Normalized persistence: completion audit and cutover checklist

Audit baseline: `0a2c79a1` (PR #196 merged, 2026-11-10); runtime progress below
includes the subsequent customer invoice/credit editing slice. This is the remaining-work
ledger for issue #75, not a claim that format 3 is ready for normal GUI use.
The [design and migration notes](normalized-persistence.md) describe implemented
slices; this ledger distinguishes migration preservation from runtime completion.

## Finding

The normalized migration/repository foundation is substantial, but **normal
application cutover has not happened**. New databases still initialize as format 2;
GUI startup rejects format 3. A successful converter or CLI command is not evidence
that the corresponding GUI editing workflow works without legacy object storage.
There is no defensible completion percentage or fixed remaining PR count yet.

Source evidence:

- `dataformat/DataFormatManager.java`: `CURRENT_DATA_FORMAT_VERSION = 2`,
  `NORMALIZED_DATA_FORMAT_VERSION = 3`; `checkAndInitialize` initializes new
  catalogues to the current format and rejects newer formats.
- `Bookkeeping.java`: `startupDatabase` calls that check and then
  `SSDB.startupLocal`, not the normalized CLI runtime.
- `cli/BokfriRuntime.java`: `open` dispatches format 3 to plain JDBC and normalized
  stores; `database()` deliberately rejects normalized storage. This is a useful
  boundary to preserve, not a reason to remove the GUI's format guard prematurely.
- `se/swedsoft/bookkeeping/data/system/SSDB.java`: `startupLocal` creates legacy
  tables/triggers and loads selected company/year using JDBC object values. Its
  numerous domain operations still persist objects.

Paths above starting with `dataformat/`, `cli/` or `Bookkeeping.java` are relative
to `src/main/java/org/fribok/bookkeeping/`; the `se/` path is relative to
`src/main/java/`.

## Domain coverage at the audit baseline

All rows below have explicit staging schema/conversion support, subject to the
known compatibility restrictions below. The runtime column inventories public
`Normalized*Store` APIs, **not** complete GUI support. Add/list is not full CRUD;
private conversion helpers and demo-only inserts do not establish editing support.

| Domain | Normalized runtime evidence | Remaining cutover work |
|---|---|---|
| Company and accounting year | `NormalizedAccountingStore`: reads, selection, company/year add and update; company deletion; company/year creation CLI | Route GUI lifecycle, selection, edits and transaction ownership through normalized services; verify every GUI action |
| Accounts, opening balances, budgets, vouchers | Accounting store/writer: reads, account/voucher writes and deletes, opening-balance replacement; budget reads; explicit voucher calculation contexts | Inventory budget editing and account-plan mutation workflows; correction-link/numbering parity; wire GUI posting and rollback |
| Global account-plan catalogue | `NormalizedAccountPlanCatalogStore`, spreadsheet import/export, PDF, year creation | Wire GUI catalogue workflows without legacy startup seeding |
| Customer, supplier, product registers | `NormalizedRegisterStore`: read/add/delete, pure hydration and stored references | Complete edit semantics, reference/deletion rules and GUI integration; do not infer update support from insert helpers |
| Shared lookups, projects, result units | Register store: lookup/dimension reads and dimension-set replacement | Complete GUI-required edit/delete/create operations and reference validation |
| Voucher templates and automatic distributions | Accounting store: template read/add; schema/converters preserve both domains | Template edit/delete and automatic-distribution runtime operations; calculation/GUI parity |
| Customer invoices and credits | Stable-ID unbooked CRUD; booked metadata; atomic order detachment/deletion, journal posting and booked-invoice credit creation services; CLI/PDF workflows | Unsupported direct booked/correction graphs, complete interest/copy/convert operations and GUI adapters |
| Supplier invoices and credits | `NormalizedSupplierInvoiceStore`: read/add, numbering, sums and entered-state marking | General edit/delete and complete GUI booking/credit/correction workflows |
| Inpayments and outpayments | Respective stores: read/add, numbering and entered-state marking; explicit voucher calculations | General edit/delete, payment differences, correction and GUI posting transactions |
| Periodic invoices | V20 complete snapshots/fingerprints for supported graphs; V19 recovery | Typed load/edit/delete/generation service and GUI integration; unsupported graphs below |
| Orders and quotations | `NormalizedSaleDocumentStore`: list/find/add/update/delete with stable SQL identities and savepoints | GUI adapters, numbering/copy/conversion-to-invoice behavior and transaction tests |
| Purchase orders | `NormalizedPurchaseOrderStore`: list/find/add/update/delete with savepoints | GUI adapters, numbering, copy and conversion workflows |
| Inventory and in/out deliveries | `NormalizedStockDocumentStore`: list/find/add/update/delete; `NormalizedStockStore`: totals projection | GUI adapters, numbering and stock effects across complete workflows |
| Own reports | V18 schema/converter/fingerprint | Typed report load/create/edit/delete and rendering integration; `SSOwnReportDialog` and `SSOwnReportFrame` still call `SSDB` |

The store inventory is in `src/main/java/org/fribok/bookkeeping/dataformat/`.
`NormalizedSaleValuesStore` and `NormalizedStockStore` are projections, not editable
document repositories. Public APIs are only the starting inventory: each row must
be checked against actual GUI commands, model setters, copy methods and triggers.

## Known compatibility decisions (not silent repair)

- [x] V18-to-V19 account-plan catalogue recovery preserves the current normalized
  database and uses an operator-trusted, SHA-verified legacy source.
- [x] V19-to-V20 periodic snapshot recovery does likewise (PR #196). Matching is by
  surviving legacy identity and full projection, not displayed document number.
- [ ] Represent supported periodic invoice subclasses and correction-linked nested
  voucher graphs, or explicitly define and test a supported-source policy that
  reports these cases before activation. Current converters reject them rather
  than lose data. A rejection must not be described as universal migration support.
- [ ] Specify the handling of already-V20 incomplete/manual catalogues. Recovery
  currently refuses them. Do not build speculative repair tooling unless a real
  supported fixture needs it; keep refusal and manual assessment documented.
- [ ] Establish the supported historical fixture matrix, including correction
  graphs, nullable/duplicate historical values, decimal precision and old class
  variants listed in the design's open points. State exclusions explicitly.

These are compatibility gates. They are not permission to overwrite accounting
entered after normalization, invent missing snapshots or migrate automatically
from an arbitrary old backup.

## Ordered completion gates

A gate is complete only with the evidence named below. Each implementation PR
must identify its gate and the checklist items it closes. Splitting a gate across
PRs is acceptable; adding a new prerequisite requires updating this ledger with
source/fixture evidence and an acceptance test.

### G1 — Close the runtime operation inventory

- [ ] Expand each matrix row into its GUI actions and required repository/service
  operations; explicitly list missing operations rather than assuming CLI parity.
- [x] Customer invoice/credit unbooked graph edit/delete by stable SQL identity;
  local rollback/retry, null/default-account preservation, reference protection
  and disk reopen tests. This is repository coverage, not GUI or booked-edit parity.
- [x] Explicit customer invoice/credit non-posting metadata updates (printed, OCR,
  reminder count and interest-invoiced flags), including on booked documents, with
  compare-and-set protection and no financial graph replacement. Reminder recording
  guards overflow. This does not complete interest-invoice generation or GUI printing.
- [x] Atomic unbooked invoice deletion with explicit company-scoped order detachment;
  no guessed duplicate-number ownership, payment/credit rewriting or ledger deletion.
- [x] Explicit booked customer-invoice correction by credit creation: stable source
  identity, source/balance/number revalidation, available-balance reservation and
  atomic credit graph insertion without rewriting the booked original or ledger.
  Credit posting remains a separate explicit customer journal transaction.
- [ ] Implement remaining typed operations, including unsupported direct booked
  financial/correction graphs, payment/register edit/delete, periodic generation/
  editing, own reports and helper/lookup/budget workflows from the action inventory.
- [ ] For each write prove company/year isolation, identity/counter semantics,
  late-failure rollback, retry, detached/null snapshot preservation and reopen.
- [x] Shared normalized customer invoice/credit journal planning and posting:
  regenerate from stable identities, reject stale financial previews, and change
  snapshots, entered flags, ledger rows and the journal counter under one savepoint.
  Both CLI customer journals now use this service and commit once on success.
- [ ] Establish corresponding posting services for remaining document families;
  connect the GUI workflows. Repository-local savepoints alone do not prove
  whole-workflow atomicity.

**Next implementation slices:** general document creation, row insertion/reordering, remaining tax flags and currency-code/reference
editing, remaining document-family workflows, and explicit production ownership/bootstrap
only once the cutover gates are met. The restricted normalized view supports credit requests,
metadata editing, bounded unbooked dates and existing-row amount/detail/account/VAT-code editing, confirmed unbooked deletion
and explicit customer journal posting, with headless and real-display tests. Production application menus remain legacy;
only the explicit owner-scoped credit actions and restricted view use these adapters. Do not
remove the format-3 startup guard merely to reach them. General booked graph/correction-link editing remains rejected;
this is not complete legacy GUI parity, and G1–G4 remain unfinished.

Customer invoice/credit action inventory (active menu callers remain legacy):

| Action / current caller | Repository evidence | Still required |
|---|---|---|
| Create / `SSInvoiceDialog` via `InvoiceService.create`; legacy credit dialogs via `SSDB.addCreditInvoice` | Explicit invoice factory/credit service plus `CustomerCreditDialogAdapter` and `SSCreditInvoiceDialog.normalizedDialog`: restricted date/amount preview/create, dedicated sessions, commit-confirmed callback | Production owner/bootstrap integration and general invoice creation adapter; explicit normalized source selection, credit routing and list refresh exist, but the legacy row editor is not the normalized request form |
| Edit / `SSInvoiceDialog` via `InvoiceService.update`; `SSCreditInvoiceDialog` via `SSDB.updateCreditInvoice` | Unbooked graph replacement, booked metadata and explicit booked-invoice credit creation | Row insertion/reordering, remaining tax flags, currency-code/reference workflows and production integration; restricted metadata, unbooked dates, tax-exemption/stored VAT/exchange rates and existing-row amount/detail/account/VAT-code adapters exist. Unsupported direct booked graph/correction-link editing stays refused |
| Delete / both document frames | `NormalizedCustomerDocumentService` detaches scoped orders and deletes the unbooked invoice graph atomically; payment/credit guards and ambiguous-link protection remain | Restricted preview/confirmation adapter and current-view refresh exist; production legacy screen/cache replacement remains. Booked invoices require financial correction rather than deletion |
| Journal / `InvoiceService.planJournal` and `commitJournal` | `NormalizedCustomerJournalService` provides whole invoice/credit journal atomicity, stable-ID posting, stale-financial-plan rejection and caller-owned commit; created credits post through the same explicit service | Restricted explicit preview/confirmation adapter and current-view refresh exist; production integration and other document-family posting adapters remain. Unsupported voucher correction graphs stay a compatibility boundary |
| Print/remind/copy/convert | Explicit metadata compare-and-set and reminder-count service; existing CLI PDF generation remains read-only | GUI print success callbacks, full interest-invoice generation and generated-document/copy actions; SQL metadata writes do not establish exactly-once external printing |

Renumbering protects both the old and proposed invoice number from payment,
credit or order references, including dangling historical references. Inserting a
new invoice cannot silently adopt such a reference. Null and duplicate historical
numbers remain readable; edits target SQL identity, not number. These guards are
fail-closed repository boundaries, not a new claim of complete business validation.

### G2 — Introduce a shared normalized application boundary

- [x] Explicit normalized credit-request adapter and dialog entry point with no
  legacy JDBC: dedicated per-action sessions, read-only format/history validation,
  shared application transaction gate, off-EDT backend work and commit-confirmed
  EDT notification. Confirmed saves are replay-safe within the dialog; uncertain
  commits block further requests pending external refresh.
- [x] Add explicit owner-scoped customer-document sessions and route invoice Credit /
  credit-invoice New through an installed binding before legacy row lookup. Select
  booked sources by stable SQL ID off-EDT; qualify commit notifications and invalidate
  old selections/dialogs on replacement or owner close. Unbound owners retain legacy behavior.
- [x] Provide an explicit read-only normalized customer-document panel: immutable
  invoice/credit snapshots, explicit totals/balances, stable-ID sorted selection, background
  loading and confirmed-commit refresh of both tables. Replace/close discards stale loads;
  failures retain the last good snapshot. No legacy frame models/caches are used.
- [x] Add stable-ID invoice/credit metadata actions and a restricted normalized dialog:
  printed/OCR/reminder/interest flags only, four-field stale-value protection, dedicated
  gated sessions, off-EDT work, rollback/retry and uncertain-commit blocking. Confirmed
  saves refresh only the current view; selection replacement invalidates pending dialogs.
  This does not enable financial editing or make legacy document editors normalized-safe.
- [x] Add preview/confirm deletion for unbooked invoices and credits from the explicit
  view: source-graph/order-set stale tokens, booked/payment/credit guards, atomic invoice
  order detachment, dedicated sessions and commit-confirmed current-context refresh.
  Destructive confirmation is explicit; late failures roll back and unknown commits block
  blind retry. No legacy caches or booked financial edits are introduced.
- [x] Add explicit invoice/credit journal actions to the restricted normalized view:
  immutable stable-ID document/voucher previews, allocated journal/voucher numbers,
  inclusive range input and explicit confirmation, shared dedicated transaction gate,
  whole-journal atomic posting and current-context commit refresh. Changed dates/financial
  inputs or numbering require a new preview; uncertain commits block blind reposting.
- [x] Add bounded unbooked invoice/credit date/due-date and existing-line quantity/price
  editing: immutable source/patch values, full source-graph + rounding stale token, exact
  decimal representability, stable stored row keys, fresh totals preview/confirmation and
  narrow SQL updates preserving all other columns/snapshots/references. Booked edits remain
  refused; subsequent entries extend the supported draft fields without graph replacement.
- [x] Extend the bounded unbooked editor with existing-line descriptions and nullable discounts:
  fresh preview/confirmation, exact decimal storage and changed percentage bounds 0–100,
  narrow atomic updates, stale-source protection and null/empty/historical-value preservation.
  Booked edits stay refused; subsequent entries add bounded account and tax controls.
- [x] Add selected-year existing-line account and stored-document VAT-code editing:
  active 3xxx accounts for new non-null assignments, persisted VAT-code
  names and present bounded stored rates for new nonzero selections, immutable context and
  account-plan/source stale tokens, narrow atomic updates and real-display confirmation tests.
  Existing historical/null accounting values can be preserved or explicitly cleared in drafts;
  posting remains separately validated. Later entries add required-dimension, header-tax and
  exchange controls; currency-code/reference workflows stay separate.
- [x] Add bounded document tax-exemption and stored-rate editing: exact changed non-null
  rates within 0–100, explicit non-null exemption flag, nullable raw rate preservation,
  complete used-rate validation for changed taxable headers, simultaneous row-code/rate
  previews, narrow atomic updates, stale-source guards and real-display confirmation.
  Missing used rates are not silently replaced by defaults. EU/other tax flags, currency codes,
  row structure and general creation remain separate work; booked edits stay refused.
- [x] Add stored exchange-rate editing without changing document/company currency codes:
  exact positive changed rates, explicit currency context and same-code rate-1 guard,
  unrounded converted-total preview with separate journal-rounding warning, source/base-code
  stale tokens, one-column atomic update and real-display confirmation. Nullable/historical
  stored rates remain preservable when unchanged; catalogue rates/defaults are not used.
  Currency-code/reference changes, row structure and general creation remain separate work.
- [x] Add company-scoped project/result-unit editing on existing unbooked rows and allow
  required sales accounts when the proposed row supplies valid required dimensions. New
  projects must not be marked concluded; new result units must belong to the company.
  Unchanged historical/null/empty/closed values are preserved without repair. Source tokens
  include dimension catalogues; narrow updates, rollback/retry, real-display confirmation and
  separate dimension-preserving invoice/credit posting are tested. Row structure stays separate.
- [x] Add explicit existing-row removal from the unbooked invoice/credit draft editor:
  stable removed-key/totals preview, fresh confirmation, surviving-key preservation without
  renumbering, stale-source protection and atomic rollback/retry after a late delete failure.
  Reload discards local removals. The final row requires the existing explicit document-delete
  workflow rather than silently creating an empty document. Row insertion/reordering remain open.
- [ ] Install bindings/views from production normalized application bootstrap, replace
  legacy screen/cache paths and remaining credit-frame/editor operations. Format-3 startup
  remains rejected; these restricted integration boundaries do not make the full credit
  frame or other document screens normalized-ready.
- [ ] Define transaction/connection ownership and selection/cache notifications
  usable by both GUI and CLI. Do not reuse legacy `startupLocal` on format 3.
- [ ] Route model defaults, calculators, import/export, numbering and trigger-driven
  operations through explicit context or an audited application facade.
- [ ] Port GUI actions domain by domain behind the format-3 guard; preserve existing
  legacy behavior until cutover. Keeping a compatibility facade is acceptable;
  allowing it to issue legacy object SQL on normalized storage is not.
- [ ] Add a test guard that fails any normalized application workflow attempting
  legacy domain SQL/object deserialization. Do not merely count singleton calls.

Coupling signal at baseline: 142 GUI Java files, 40 data Java files and 21 calc
Java files contain the literal `SSDB.getInstance()`. These counts include safe
selection calls and legacy-only overloads; they are **not** 203 proven defects or
203 separate tasks. Reproduce by recursively searching those three directories
under `src/main/java/se/swedsoft/bookkeeping/`, counting matching files, not lines.

### G3 — End-to-end parity and backup/restore

- [ ] Run normalized GUI workflows for company/year selection, register editing,
  voucher posting/correction, invoices/credits/payments, periodic generation,
  purchasing, stock and custom reports. Cover copy/convert/import/export actions.
- [ ] Verify accounting journals, taxes, balances, dimensions and stock against
  equivalent legacy fixtures, including failure and restart midway through writes.
- [ ] Exercise the GUI backup/restore paths, not only CLI physical ZIP helpers.
  `SSBackup`/`SSBackupDatabase` still use object streams: distinguish metadata
  compatibility from serialized domain payloads and isolate historical readers.
- [ ] Restore old supported backups through the documented migration boundary and
  restore new normalized backups without legacy domain deserialization.
- [ ] Pass full headless and GUI-enabled tests, assembled-JAR smoke, fresh-install,
  upgrade, rollback/interruption and reopen checks on Linux, Windows and macOS.
  Current headless success with GUI skips does not satisfy this gate.

### G4 — Default cutover and legacy retirement

- [ ] Initialize genuinely new databases with normalized schema/defaults only;
  no domain `OTHER` columns and no legacy trigger/table bootstrap.
- [ ] Enable GUI format-3 opening only after G1–G3 evidence, with explicit supported
  schema/version handling and actionable recovery messages.
- [ ] Make supported legacy activation an explicit verified staging operation;
  preserve original sources and rollback archives, including failure recovery.
- [ ] Remove legacy domain persistence from normal GUI/CLI runtime; retain the
  historical reader only in the migration/restore compatibility boundary.
- [ ] Audit packaged resources and normal runtime paths for domain object SQL and
  serialized domain payloads; keep `bookkeeping.config` GUI preferences out of
  scope as specified by the design, not as an accidental blocker.
- [ ] Update user/operator documentation and verify the original #75 definition
  of done before declaring the transition complete.

## Status and scope control

Issue #75 was closed on GitHub before this audit, but its documented definition of
done is not met. GitHub closure is not completion evidence. This checklist records
the remaining work regardless of that issue's tracker state.

This audit changes no runtime behavior, enables no migration or GUI feature, and
adds no blanket upgrade/repair command. Report progress by completed operations,
verified end-to-end workflows and gates, not accumulated PRs or total test count.
Update this ledger with each cutover PR; avoid treating every optional recovery
feature as a prerequisite for normal application cutover.
