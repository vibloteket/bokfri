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
| Customer invoices and credits | `NormalizedInvoiceStore`: stable-ID list/find/add, unbooked edit/delete, numbering, sums and atomic entered-state marking; several CLI/PDF workflows | Booked correction/metadata workflows, explicit order detachment and all GUI lifecycle integration/posting transactions |
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
- [ ] Implement remaining typed operations, including booked invoice/credit
  correction/metadata workflows, payment/register edit/delete, periodic generation/
  editing, own reports and helper/lookup/budget workflows from the action inventory.
- [ ] For each write prove company/year isolation, identity/counter semantics,
  late-failure rollback, retry, detached/null snapshot preservation and reopen.
- [ ] Establish explicit posting services so documents, vouchers, entered flags
  and counters change in one transaction. Repository-local savepoints alone do
  not prove whole-workflow atomicity.

**Next implementation slice:** explicit customer invoice/credit posting and booked
metadata/correction workflows, including transaction-wide ledger/document/counter
atomicity. The new repository deliberately rejects general edit/delete on booked
documents; this restriction is not legacy GUI parity. Order detachment before
invoice deletion must likewise become an explicit atomic workflow rather than
silently clearing number-based links. Do not enable the GUI merely because
unbooked CRUD now passes.

Customer invoice/credit action inventory (GUI callers remain legacy):

| Action / current caller | Repository evidence | Still required |
|---|---|---|
| Create / `SSInvoiceDialog` via `InvoiceService.create`; credit dialogs via `SSDB.addCreditInvoice` | Complete snapshot insertion under a local savepoint; numbering/counters caller-owned | Shared application service, validation and counter transaction |
| Edit / `SSInvoiceDialog` via `InvoiceService.update`; `SSCreditInvoiceDialog` via `SSDB.updateCreditInvoice` | Stable-ID replacement for unbooked snapshots; booked edits refused | Booked metadata/correction policy and service, then GUI adapter |
| Delete / both document frames | Owned graph deletion for unbooked documents; invoice payment/credit/order references protected | GUI confirmation, cache notifications, atomic order detachment and supported booked workflow policy |
| Journal / `InvoiceService.planJournal` and `commitJournal` | Existing entered-state snapshot write is now locally atomic and refuses ambiguous numbers | Whole journal ledger writes, flags and counters in one transaction; credit journal equivalent |
| Print/remind/copy/convert | Existing PDFs and detached reads do not establish mutation parity | Inventory and port flag/reminder/interest updates and generated-document/copy actions through explicit services |

Renumbering protects both the old and proposed invoice number from payment,
credit or order references, including dangling historical references. Inserting a
new invoice cannot silently adopt such a reference. Null and duplicate historical
numbers remain readable; edits target SQL identity, not number. These guards are
fail-closed repository boundaries, not a new claim of complete business validation.

### G2 — Introduce a shared normalized application boundary

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
