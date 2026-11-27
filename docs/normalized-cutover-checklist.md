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
| Supplier invoices and credits | Stable-ID CRUD and exact snapshots/V21 nanos; atomic journals; explicit session/view/menu and guarded editors for name/reference, existing-row descriptions, account/project/result-unit allocations and quantity/price/freight, dates, supplier-number links (unique company register; stored name/defaults unchanged), payment-term links (no date recalculation), VAT/rounding, rate and currency code. Complete-source/reference guards, fresh confirmation, field-only SQL and shared uncertain blocking. Untouched raw history and all non-edited fields preserved; description edits keep money/stock unchanged and survive journal posting. Currency changes reinterpret, never convert numeric amounts or apply catalogue rates | Whole-supplier/default application, row insertion/removal; linked workflows/order detachment, booked/bank-exported corrections, creation/production bootstrap. Explicit bindings do not establish full workflow parity |
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
- [x] Add bounded supplier invoice/credit stable-ID snapshot replacement and deletion, with
  atomic inserts, updates, deletes and booking primitives. Require caller-owned transactions;
  preserve header identities/legacy IDs, raw nullable accounts/totals, ordered rows, main/manual
  vouchers, dimensions, flags and counters. Booked/bank-exported graph edits, referenced
  supplier invoice mutation/deletion and credit-source retargeting remain refused. Purchase
  orders/payments/credits are not silently detached or rewritten. Booking replaces only the
  main voucher, preserving the existing manual correction snapshot. V21 widens only supplier
  voucher timestamps to nine fractional digits; upgrade-value preservation, nanosecond
  round-trip, rollback/retry, stock effects and two real file reopens are tested. This is G1
  repository coverage, not supplier GUI integration or whole-journal transaction ownership.
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

**Next implementation slices:** general document creation, compound-product expansion/row reordering,
remaining tax flags and linked-reference workflows, remaining document-family workflows, and explicit production ownership/bootstrap
only once the cutover gates are met. The restricted normalized view supports credit requests,
metadata editing, bounded unbooked financial/header/dimension editing, manual/product row insertion/removal,
guarded unreferenced currency-code changes, confirmed unbooked deletion and explicit customer journal posting,
with headless and real-display tests. Production application menus remain legacy;
only the explicit owner-scoped credit actions and restricted view use these adapters. Do not
remove the format-3 startup guard merely to reach them. General booked graph/correction-link editing remains rejected;
this is not complete legacy GUI parity, and G1–G4 remain unfinished.

Customer invoice/credit action inventory (active menu callers remain legacy):

| Action / current caller | Repository evidence | Still required |
|---|---|---|
| Create / `SSInvoiceDialog` via `InvoiceService.create`; legacy credit dialogs via `SSDB.addCreditInvoice` | Explicit invoice factory/credit service plus `CustomerCreditDialogAdapter` and `SSCreditInvoiceDialog.normalizedDialog`: restricted date/amount preview/create, dedicated sessions, commit-confirmed callback | Production owner/bootstrap integration and general invoice creation adapter; explicit normalized source selection, credit routing and list refresh exist, but the legacy row editor is not the normalized request form |
| Edit / `SSInvoiceDialog` via `InvoiceService.update`; `SSCreditInvoiceDialog` via `SSDB.updateCreditInvoice` | Unbooked graph replacement, booked metadata and explicit booked-invoice credit creation | Compound expansion/row reordering, remaining tax flags, linked-reference workflows and production integration; restricted metadata, unbooked financial/tax/dimension editing, manual/product insertion/removal and guarded unreferenced currency changes exist. Unsupported direct booked graph/correction-link editing stays refused |
| Delete / both document frames | `NormalizedCustomerDocumentService` detaches scoped orders and deletes the unbooked invoice graph atomically; payment/credit guards and ambiguous-link protection remain | Restricted preview/confirmation adapter and current-view refresh exist; production legacy screen/cache replacement remains. Booked invoices require financial correction rather than deletion |
| Journal / `InvoiceService.planJournal` and `commitJournal` | `NormalizedCustomerJournalService` provides whole invoice/credit journal atomicity, stable-ID posting, stale-financial-plan rejection and caller-owned commit; created credits post through the same explicit service | Restricted explicit preview/confirmation adapter and current-view refresh exist; production integration and other document-family posting adapters remain. Unsupported voucher correction graphs stay a compatibility boundary |
| Print/remind/copy/convert | Explicit metadata compare-and-set and reminder-count service; existing CLI PDF generation remains read-only | GUI print success callbacks, full interest-invoice generation and generated-document/copy actions; SQL metadata writes do not establish exactly-once external printing |

Renumbering protects both the old and proposed invoice number from payment,
credit or order references, including dangling historical references. Inserting a
new invoice cannot silently adopt such a reference. Null and duplicate historical
numbers remain readable; edits target SQL identity, not number. These guards are
fail-closed repository boundaries, not a new claim of complete business validation.

### G2 — Introduce a shared normalized application boundary

The opt-in host also exposes [manual-voucher creation](normalized-manual-voucher.md)
for its explicitly selected year: active accounts, explicit company-scoped projects/result units,
required-dimension and project closure checks, exact cent-balanced rows, preview with current
labels/numbering and atomic single-use posting. It creates no dimension defaults or catalogue
records. This shares the owner and cross-family uncertainty boundary, but is not dimension
catalogue editing, correction/import/template parity or production voucher-frame integration;
G2 remains incomplete.

The [read-only voucher register](normalized-voucher-register.md) now provides explicit
100-header pages and SQL-identity-scoped opening in the same lease. Raw nullable values,
ordered rows, corrections and edit flags remain visible, with current catalogue labels
separately identified. Reads use the shared gate, background JDBC and host interlocks; no
writes, inferred totals, corrections or automatic post-selection are introduced. This is
stored-journal inspection, not complete voucher-frame or accounting-report parity.

The separate [standalone reversal](normalized-voucher-reversal.md) command now adds explicit
source/date/description preview and atomic new reversed rows plus reciprocal header links.
Original rows are preserved. Sources with document snapshots, ambiguous same-company numbers,
existing correction graphs or unsupported historical edits are refused. All seven document
snapshot families are guarded, including periodic templates/generated snapshots. This is one
bounded correction operation, not general row editing, document unbooking or full correction parity.

The [financial-report views](normalized-financial-reports.md) now provide read-only trial balance
and account ledger with explicit periods/accounts, stored opening balances and original/reversal
entries. They reuse the shared formulas with explicit year-plan account resolution rather than
ambient row getters. Normalized CLI financial reports use that boundary too, with legacy/normalized
JSON/PDF parity checks. SQL is rolled back, pending reads interlock the host, and no report defaults,
balancing entries or export UI are added. These bounded views do not complete legacy report parity,
production integration, accounting gates or G2/G3.

The opt-in workspace also supports [explicit empty-year creation](normalized-year-creation.md)
for an existing selected company/year, with preview/confirmation and shared uncertainty
blocking. An existing company with zero years can also use the guarded
[first-year command](normalized-first-year-creation.md) without an active document lease.
The separate [empty-company command](normalized-company-creation.md) now adds explicit
identity/currency/VAT-period/rounding preview and confirmation, followed by separate first-year
creation and explicit Apply; it seeds no accounting defaults and preserves active context.
These are restricted G2 commands, not full company-settings parity, carry-forward,
production bootstrap or completion of G2.

- [x] Host the restricted customer/supplier views together in an explicit
  [normalized document workspace](normalized-document-workspace.md): background catalogue and
  company/year validation, explicit no-default choices, paired selection replacement, existing
  owner-scoped menu bindings, last-good context on invalid choices, close/late-callback fencing,
  and state-only observation that retires both families after an uncertain commit without legacy
  fallback. Three-platform display CI now selects the owner/workspace test classes. This is a
  dedicated opt-in host, not production opening, a legacy-frame retrofit or complete GUI parity.

- [x] Add an explicit [shared application owner](normalized-application-owner.md) for the
  restricted customer/supplier document sessions: immutable scoped company/year choices,
  validated replacement and closure, one writer gate, full current-schema validation on every
  connection and cross-family uncertain-commit blocking. Both real document views are tested
  against the same owner. No legacy/global selection or startup seeding is used. This is an
  opt-in ownership boundary, not production host/menu installation, automatic cache/UI
  notifications or complete command ownership for all domains; the broader items below remain open.

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
  Guarded unreferenced code changes are covered below; linked-reference workflows, row reordering
  and general creation remain separate work.
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
  workflow rather than silently creating an empty document (unless explicitly replaced below).
- [x] Add explicit manual-row insertion to that financial form. New rows require description,
  positive exact quantity, nonnegative price, optional 0..100 discount, active selected-year
  3xxx account, stored document VAT code/rate and company-scoped required dimensions.
  New keys exceed the original maximum; surviving keys and raw snapshots are not rewritten.
  Added keys and totals require fresh preview/confirmation; reload discards local additions.
  Removing a local addition does not delete SQL data. A replacement row permits removing the
  final existing row, but an empty result is refused. Savepoint rollback covers earlier edits
  and partial inserts; source/catalogue checks and uncertain-commit blocking still apply.
  Both invoice/credit view routes and separate journal dimension groups are tested.
  Product-linked insertion is bounded below; row reordering and creation remain separate.
- [x] Add explicit company-product selection for new rows, snapshotting the selected number/unit
  and an editable description while keeping price/account/VAT/dimensions explicit. No catalogue
  price, currency conversion or legacy accounting defaults are used. Missing, foreign, expired,
  duplicate-number and compound products are refused; existing product/unit snapshots are
  read-only and preserved. Product catalogue context participates in stale checks; preview lists
  new product references and the unchanged document stock flag. Stock-bearing inserted rows and
  changed existing stock quantities require exact 32-bit integers; unchanged historical values
  are preserved. Store-level scope checks, atomic partial-insert rollback, uncertain-commit
  blocking, both GUI routes and separate dimension-preserving posting are tested.
  Compound expansion, row reordering, general creation and production bootstrap remain open.
- [x] Add guarded document currency-code changes with explicit positive stored rate, same-code
  rate-1 guard and read-only company currency. The combo retains the raw current code (including
  null/blank history) and never selects a default; choosing another code clears the rate for fresh
  entry. Preview labels both old/new document units, unrounded base equivalent and no repricing.
  Only uniquely numbered, unreferenced documents can change code: payments, credit/order links,
  source-linked credit corrections and unresolved order-number snapshots are refused. Null/blank
  order snapshots and the exact legacy no-orders marker are recognized as empty, not parsed.
  Reference guards run at preview and save; catalogue-code/base changes invalidate previews,
  while unused catalogue rates are never read. Narrow code/rate writes, late-failure rollback,
  unknown commits, foreign reference isolation and both real-display refresh routes are tested.
  This does not implement broader linked-reference changes or production cutover.
- [ ] Install bindings/views from production normalized application bootstrap, replace
  legacy screen/cache paths and remaining credit-frame/editor operations. Format-3 startup
  remains rejected; these restricted integration boundaries do not make the full credit
  frame or other document screens normalized-ready.
- [ ] Complete transaction/connection ownership and selection/cache notifications
  usable by both GUI and CLI. The explicit owner above covers company/year catalogue reads and
  the two restricted document session families, not all commands or host notifications.
  Do not reuse legacy `startupLocal` on format 3.
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


## Guarded initial opening balances (2026-11-23)

The seventh opt-in workspace tab supports explicit initial balances for an empty selected
year only: active balance accounts, signed cent-exact amounts, balanced preview and separate
atomic confirmation. Any existing balance or journal blocks insertion; there is no
replacement, carry-forward, rounding, year-setting change or inferred balancing account.
Owner-bound single-use previews, stale-input checks, rollback and shared uncertain-commit
blocking apply. See [normalized-initial-opening-balances.md](normalized-initial-opening-balances.md)
for the workflow and boundaries. Format-3 normal startup stays guarded and format-2
creation remains unchanged. This does not complete G2/G3 or issue #75.


## Guarded balance-sheet and income-statement views (2026-11-23)

The existing financial-report tab also offers two explicit, read-only report kinds:

- **Balance sheet:** enter the inclusive **To** date; **From** is disabled and unused.
  Rows show signed debit-minus-credit balances from stored year opening and eligible
  entries through that date. Totals expose assets, liabilities/equity, current-year
  result and difference. Current result excludes opening amounts; the visible difference
  is assets minus liabilities/equity minus current result, not an accounting certification.
  No rounding or adjustment is applied to force a zero difference.
- **Income statement:** enter explicit inclusive **From/To** dates. Only period result-account
  movement is included, credit minus debit. Opening amounts and earlier entries are excluded.
  The period result is shown; there is no inferred comparison period or balancing adjustment.

Zero rows are omitted by the existing shared calculator. Current catalogue labels, inactive
accounts and eligible historical rows follow the same explicit-account resolution as trial
balance/ledger. Generate is explicit; changing inputs clears results without automatically
reading storage. Both kinds use the same owner lease, serializable read/rollback transaction,
background worker, stale-callback fencing, host interlock and uncertain-commit block.

No new tab, PDF/export UI, write command, legacy-frame integration, schema migration,
dimension filtering, backup/restore or default cutover is introduced. Format-3 normal
startup remains guarded and format-2 creation is unchanged. These views do not complete
G2/G3 or the technical definition of done for issue #75.


## Guarded displayed-report snapshot PDF (2026-11-23)

The financial-report tab can export its successfully generated immutable display snapshot
as a new PDF, with explicit destination selection and atomic no-overwrite publication.
No SQL re-read, recalculation, rounding or accounting write occurs during export.
Input/context changes disable export until Generate succeeds again. Accepted exports may
finish against their original snapshot after retirement; late UI callbacks are fenced.
See [normalized-report-snapshot-pdf.md](normalized-report-snapshot-pdf.md) for file safety,
hard-link filesystem requirements, snapshot/legacy-layout boundaries and regression tests.
This does not activate normal format-3 startup or establish full G2/G3/#75 completion.


## Guarded read-only VAT report (2026-11-24)

The existing financial-report tab now supports explicit inclusive-period VAT boxes and
VAT snapshot PDF export, with current account-code mappings and the existing per-account
whole-unit truncation rules. The lease-owned read rolls back and never commits; normalized
VAT aggregation uses explicit row account numbers, not legacy account/validity getters.
No settlement, filing, currency conversion, new tab, schema/CLI command or default cutover.
See [normalized-vat-report-view.md](normalized-vat-report-view.md) for exact rules and bounds.
This does not complete G2/G3 or issue #75 technical requirements; startup guard stays enabled.


## VAT settlement planning isolation (2026-11-24)

The existing hydrated-year/explicit-input VAT planner now resolves only supplied account
numbers and creates an unnumbered voucher, leaving numbering to its explicit writer.
The legacy SSDB overload retains its existing lookup/numbering path; amounts and rounding
are unchanged. See [normalized-vat-settlement-isolation.md](normalized-vat-settlement-isolation.md).
This is a backend prerequisite, not settlement UI activation, a new posting command,
period locking/filing, or G2/G3/#75 completion. The guarded VAT view remains read-only.


## VAT rounding policy audit (2026-11-24)

The [rounding audit](vat-rounding-policy-audit.md) identifies two distinct blockers:
report truncation before account aggregation, and settlement truncation of the raw net
instead of deriving control from declared tax fields. Six executable examples cover
positive/negative corrections, cross-field fractions, payable/refund and mixed signs.
The audit cites whole-krona legislation and Skatteverket's field-49 formula, separates
the field-total interpretation from complete filing compliance, and changes no production
behavior. The guarded view/PDF still use legacy calculations and are not validated filing
or settlement previews. Do not activate settlement on the strength of planner isolation
or balanced vouchers alone; policy agreement and existing workflow safety gates remain.


## Opt-in explicit VAT declaration policy (2026-11-24)

Hydrated-year `VatService.declarationReport`/`declarationPlan` now implement an explicitly
named field-total whole-krona policy and derive control from the same declared tax fields.
Exact clearing includes every supported VAT tax-field code; settlement roles must be
explicitly coded R1/R2/A. See [explicit-vat-declaration-policy.md](explicit-vat-declaration-policy.md).
Existing compatibility `report`/`plan`, guarded VAT view/PDF and CLI do not select this policy.
This backend prerequisite does not add an owner entry point, new posting command, period
lock, stale/duplicate-source checks or uncertain-commit handling. Those safety gates and
explicit future report/preview/PDF policy selection remain required before activation.
Startup guard stays enabled; no default cutover, G2/G3 or issue #75 technical completion.


## Guarded explicit VAT field-total report choice (2026-11-25)

The financial-report tab now exposes the declaration report as a separately selected,
read-only choice with policy-labelled displayed-snapshot PDF export. The existing
per-account compatibility choice retains its amounts and is explicitly labelled.
The lease-owned `vatDeclarationReport` validates selected-year periods, shares the
SERIALIZABLE rollback-only read lifecycle and never invokes a planner or writer.
Input revisions fence late results after policy edits. See
[normalized-vat-declaration-report-view.md](normalized-vat-declaration-report-view.md).
No settlement workflow, filing, default cutover, guard removal or G2/G3/#75 completion.


## Guarded immutable VAT settlement preview (2026-11-25)

A separately selected read-only proposal now shows exact clearing/control/rounding
rows and the declared total, captured in one lease-owned SERIALIZABLE read. The
adapter copies immutable scalar DTOs and exposes no mutable voucher, writer, number
assignment or posting token. Display/PDF warnings say NOT POSTED and explicitly
exclude bookability/dimension/source-freshness/duplicate-period approval. See
[normalized-vat-settlement-preview.md](normalized-vat-settlement-preview.md).
Posting still needs owner transactions, source revalidation, bookability/dimension
validation, duplicate/overlap policy, period restrictions and uncertain-commit
handling. Balanced previews alone do not satisfy those gates. No settlement posting,
filing, default cutover, guard removal or G2/G3/#75 technical completion.


## Read-only VAT account/dimension observations (2026-11-25)

The frozen settlement preview includes non-exhaustive observations of inactive
proposed accounts and missing required dimensions. Initially, source observations
reported dimensions omitted by account-level planning. With the dimension grouping
below, they report missing or mismatched exact group reversals, even on net-zero
accounts. Observations remain captured in the same read and displayed/exported
without mutating proposal arithmetic.
An empty list explicitly does NOT approve posting. No readiness flag, posting token,
UI/API activation or validation of all remaining gates is added. See
[normalized-vat-settlement-diagnostics.md](normalized-vat-settlement-diagnostics.md).
Owner posting transactions, complete bookability/dimension/source validation,
duplicate/overlap and lock policy, numbering and uncertain-commit handling remain
unfinished. Guard stays enabled; no default cutover or G2/G3/#75 technical completion.

### Explicit VAT settlement dimension preservation

The explicit field-total planner preserves exact raw account/project/result-unit groups,
including zero-net accounts. Preview rows/PDF freeze those keys, and diagnostics compare
exact group reversals. Control/rounding do not infer dimensions. Compatibility report/plan
amounts and startup guard are unchanged. No posting authorization or G2/G3 completion.
See [dimension preservation](normalized-vat-settlement-dimensions.md).

### Read-only VAT dimension catalogue observations

The settlement preview additionally captures exact proposed-key membership in the
selected company catalogues and project closure at proposal date, in the same
rollback-only read. Blank/unknown keys and closed/unknown-date projects are findings,
not repairs or posting approval. Other reports do not add catalogue SQL. Planner
amounts, CLI/defaults/guard and remaining posting gates are unchanged. See
[dimension catalogue diagnostics](normalized-vat-dimension-catalogue-diagnostics.md).

## Backend-only VAT settlement source reviews

A separate owner-bound read-only review captures the existing immutable preview and
private conservative source state in one rollback-only read. Rechecking compares
full scoped inputs, not only displayed totals/max voucher; it does not refresh the
old preview, authorize posting, lock sources or reserve a number. Current equality
is not transaction history and cannot guarantee a later write. Existing preview/UI/
PDF amounts/resources/defaults/guard remain unchanged. See
[source review boundaries](normalized-vat-settlement-source-review.md).

## Guarded explicit source recheck

Settlement Generate now uses the owner-bound read-only review capture. The separate
Recheck sources control reports current equality/difference in status only, without
refreshing the old table/header/PDF or approving posting. PDFs explicitly exclude
mutable check outcomes and remain frozen/exported without SQL. Edits, replacement,
closure and read failures clear review/export capability; pending/host/uncertainty
fences remain. Other report choices, amounts/defaults/guard and remaining posting
gates are unchanged. See [source recheck](normalized-vat-settlement-source-recheck.md).

## Frozen proposed row/amount observations

The settlement preview also captures [limited exact row/amount findings](normalized-vat-settlement-amount-diagnostics.md)
in the same read, without new SQL or amount changes. Proposed-row and total positions
are distinct from source references; fractional cents and storage/manual-entry
range observations do not round, repair or authorize posting. Source checks remain
status-only; old warnings/PDF stay frozen/exported without SQL. Empty findings, a
source match and balance do not approve posting or the remaining gates.
