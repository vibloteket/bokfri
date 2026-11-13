# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Bokfri starts its own version history at 1.0.0. The project is a fork of 
[Fribok](https://fribok.org/) and 
[JFS Accounting](https://sourceforge.net/projects/jfsaccounting/),
diverging from upstream Fribok after version 2.2-SNAPSHOT.

## [Unreleased]

### Added

- Add guarded document tax-exemption and stored VAT-rate editing to the normalized unbooked invoice/credit form (#75): fresh preview/confirmation, exact changed rates within 0–100, no implicit missing-rate defaults for newly taxable edited headers, narrow atomic updates and preservation of unchanged historical rates. Currency, row structure, remaining tax flags, creation and production bootstrap stay separate.
- Add explicit selected-year sales-account and stored-document VAT-code editing to the guarded normalized unbooked invoice/credit editor (#75), with fresh confirmation, account-plan staleness checks and narrow atomic updates. New assignments require active 3xxx accounts without required dimensions; missing stored VAT rates are never invented. Row structure, required-dimension editing, header tax/rate and currency editing, creation and production bootstrap remain separate work.
- Extend the guarded normalized unbooked invoice/credit editor with existing-line descriptions and nullable discounts (#75), using fresh totals preview/confirmation and narrow atomic SQL updates. Historical null/empty descriptions and unchanged out-of-range discounts survive; changed discounts must be exact percentages within 0–100. Production format-3 GUI startup remains disabled.

### Website and forum
- Keep the website header width consistent between the short download page and longer pages by always reserving vertical scrollbar space.
- Add Forum to the shared navigation on every website/help page and in the forum header, with matching narrow-screen spacing and keyboard focus styling.
- Reference the forum alongside email and GitHub in the website help/contact text and README; remove the obsolete product-registration requirement from online support and explain the separate forum account.
- Test navigation parity across all static pages and the forum header, and validate both stylesheets in CI. Apply the updated forum header/CSS manually; no container rebuild or authentication changes.

### Infrastructure
- Add bounded normalized unbooked invoice/credit date and existing-line quantity/price editing (#75):
  immutable source tokens, exact decimal validation, totals preview and explicit confirmation, stable stored row keys,
  and narrow atomic SQL updates preserving metadata, references and voucher snapshots. Full row creation/editing and
  production normalized GUI activation remain separate; booked financial edits and format-3 startup stay disabled.

- Add an explicit normalized credit-request GUI adapter and separate date/amount preview/create dialog entry point. Use dedicated sessions, read-only schema checks, a shared application gate, background JDBC work and commit-confirmed EDT refresh; prevent confirmed replay and block blind retries after uncertain commits. Preserve legacy editor/menu paths and the format-3 startup guard; application session/selection/menu wiring and other GUI adapters remain open (#75).
- Add explicit normalized credit correction planning/creation for booked customer invoices, preserving the original financial graph and ledger. Revalidate source snapshots, available balance and numbering before atomic insertion; count prior credits/payments, reject over-crediting and unrepresentable rounding, and retain caller rollback/retry. Route normalized CLI credit validate/create through the service without changing input requirements or posting implicitly. Credit journal posting remains explicit; direct booked graph edits and GUI activation remain disabled (#75).
- Add guarded normalized customer invoice/credit metadata updates, including on booked documents, without replacing financial snapshots. Compare expected printed/OCR/reminder/interest flags, preserve null/empty OCR, and guard reminder limits. Add atomic unbooked invoice deletion with explicit order-link detachment and rollback across both graphs; retain payment/credit/booked guards and refuse ambiguous historical ownership. No GUI activation, PDF side effects or new CLI commands; financial corrections and GUI adapters remain open (#75).
- Add explicit normalized customer invoice/credit journal services with stale-financial-preview checks and whole-journal savepoint atomicity across snapshots, entered flags, ledger rows and counters. Route both CLI customer journals through one final commit; use stable identities, reject incomplete/unbalanced accounting input and retain caller rollback/retry. Supply new normalized invoice account/tax defaults from the explicit company rather than the legacy singleton, without backfilling historical snapshots. Booked correction/metadata, order detachment and GUI integration remain open (#75).
- Add stable-ID normalized customer invoice/credit lookup, unbooked editing and deletion, with complete snapshot replacement and savepoint rollback/retry. Protect payment/credit/order references, refuse booked-document edits/deletion and ambiguous booking numbers, and make insertion/booking snapshot writes locally atomic. Fix nullable default-account loading; retain caller-owned numbering, counters and transaction boundaries. Update the cutover ledger: booked workflows and GUI integration remain unfinished; no GUI activation (#75).
- Audit normalized persistence completion by domain and add an ordered runtime/GUI cutover checklist with testable gates. Distinguish migration preservation from editable runtime coverage, record remaining legacy startup/default-format dependencies, and identify invoice/credit edit/delete as the next implementation slice. No runtime or activation changes (#75).
- Add explicit offline V19-to-V20 periodic snapshot recovery from an operator-trusted, SHA-verified legacy rollback ZIP. Recover only surviving periodic identities whose complete stored projection matches the source; preserve every pre-existing SQL table and post-migration accounting data, verify staging/reopen, and retain physical rollback artifacts. Require `database recover-periodic-invoices --commit`; reject changed projections, partial/existing V20 and unsafe sources instead of overwriting or inventing data. No automatic recovery or GUI activation (#75).
- Append normalized schema V20 and extend periodic-invoice staging/fingerprints to preserve explicit template presence, template/instance addresses, default-account maps, voucher headers/rows and raw added-state entries, including null/orphan keys and duplicate instance numbers. Old V19 projections or incomplete V20 markers fail closed for complete periodic verification; no automatic backfill/recovery or GUI activation. Unsupported invoice subtypes and correction-linked voucher graphs are rejected rather than silently flattened (#75).
- Add complete company-scoped normalized inventory and in/out-delivery storage with stable SQL identities and savepoint-protected insertion, replacement and deletion. Preserve nullable headers, ordered product/quantity rows, negative changes and duplicate historical document numbers without legacy numbering or database lookups. Verify stock totals reflect edits/deletes and caller rollback. No new CLI commands or normalized GUI activation (#75).
- Add complete company-scoped normalized purchase-order snapshot storage with stable SQL identities and savepoint-protected insertion, replacement and deletion. Preserve supplier/address snapshots, lookup references, nullable rates/dates, ordered rows, default accounts and invoice links without legacy JDBC access. Purchase-order copies now retain their stock-influencing flag and tolerate absent addresses; legacy business defaults remain unchanged. No new CLI commands or normalized GUI activation (#75).
- Add complete normalized order/quotation snapshot storage with company-scoped loading, insertion, replacement and deletion. Stable SQL identities allow historical null/duplicate document numbers; savepoint-protected writes preserve the caller's outer transaction on late failures. Preserve nullable dates, rates, addresses, accounts, ordered rows and cross-document references without legacy database lookups. Also fix normalized invoice loading applying a 12% business default to a stored null tax rate. This is a storage prerequisite only: no new CLI commands or normalized GUI activation (#75).
- Isolate normalized customer, supplier and product hydration from selected-company constructor defaults. Preserve absent party addresses, nullable product tax codes and empty product account maps instead of synthesizing defaults; customer/supplier copies and invoice customer assignment tolerate missing addresses while still copying present ones independently. Legacy no-argument constructors remain unchanged (#75).
- Isolate normalized customer/supplier invoice, credit-invoice and payment hydration from legacy constructor defaults. Typed stores now create empty persistence containers without selected-company, date, address or voucher initialization, preserve stored null invoice types, and leave absent voucher/correction/difference snapshots absent. Document copies preserve absent optional addresses/snapshots instead of failing during credit creation, while present snapshots are still deep-copied. Supplier/payment booking calculations treat missing auxiliary snapshots as zero adjustments without inventing them. Legacy default constructors and lazy business getters retain their behavior; GUI activation remains disabled (#75).
- Remove hidden legacy voucher-number/date and row-lookup dependencies from explicit booking calculations, normalized snapshot headers and CLI journal compression. Supply account/project/result-unit context through all six booking families and payment difference/correction totals, preserving known dimension groups and balanced payment control accounts without a selected legacy database. Keep legacy default APIs and startup/schema policy unchanged (#75).

- Enable normalized `demo recreate` preview/commit using the shared two-year demo definition, explicit invoice/payment calculations and an atomic removal/replacement transaction. Complete company-graph deletion across normalized domains while preserving unrelated companies, global templates and shared lookups; retain safe identity matching, saved-selection rules, V19 guards and same-runtime rollback/retry (#75).

- Add explicit offline `database recover-account-plans` for normalized V18 databases, using an operator-trusted, SHA-256 verified legacy rollback ZIP. Apply V19 to a copy of the current normalized database, preserve and fingerprint every pre-existing SQL table and evolved accounting data, verify disk round-trips, and activate with physical rollback artifacts. Require `--commit`, reject dirty/partial/already-upgraded catalogues, and keep ordinary startup unchanged (#75).

- Enable normalized `year create` from V19 global templates with shared date/overlap validation, independent account-plan copies and globally allocated year IDs. Save the new year/plan/accounts atomically, preserve existing years/templates and saved selections, and retain the safe guard for older normalized catalogues (#75).

- Enable normalized global `account-plan list` and XLSX export/import preview/apply using the V19 template catalogue, without company/year selection. Preserve detached template metadata, ordered accounts/flags and the existing spreadsheet format; apply imports atomically with duplicate-name and overwrite protection. Older normalized catalogues fail clearly without schema changes or replacement templates (#75).

- Preserve global reusable account-plan templates in normalized migration V19, separately from accounting-year plan copies. Verify template metadata, ordered accounts, codes and flags before commit, after reopen and after activation; reject inconsistent source keys/accounts and report verified catalogue counts. Runtime catalogue/year-creation support and upgrades of earlier normalized snapshots remain follow-up work (#75).

- Enable normalized `company create` with saved contact/payment/tax settings, default accounts and explicit currency/payment-term/unit lookups. Preserve registered lookup values and built-in fallbacks, duplicate-name validation and legacy creation behavior; roll back company/default/lookup writes together on failure (#75).

- Enable normalized `voucher show` text/JSON and optional PDF exports using the selected year's accounts/vouchers and explicit company/project/result-unit context. Preserve correction links, row edit flags, totals, nested headers and legacy constructors; explicit totals avoid singleton-backed row validity checks (#75).

- Enable normalized `account-plan pdf` exports with explicit selected company/year headers and hydrated accounts/vouchers. Preserve active-account filtering, account codes, empty plans, selected-year isolation and legacy report constructors (#75).

- Enable normalized `invoice delivery-note` (including hidden unit prices) and `invoice picking-list` PDFs with explicit company/customer/product context and nested headers. Preserve localized descriptions, stored addresses, product measurements and warehouse sorting without requiring an accounting year or legacy database initialization (#75).

- Enable normalized `invoice reminder` PDF export with explicit outstanding balances, company/customer data and nested report headers. Read stored company standard texts and customer currency without the legacy singleton; preserve reminder-date validation, credit/payment deductions, interest, reminder fees and export-only reminder counts (#75).

- Enable normalized single-invoice PDF exports, including OCR forms with optional backgrounds, using explicit company/customer/product data and nested headers. Hydrate stored company payment/contact/logo fields and complete postal addresses for sales-report headers/footers; preserve company rounding, localized descriptions, cash invoices, order references and export-only OCR behavior (#75).

- Enable normalized credit-invoice `show` and single-document PDF export without legacy database initialization. Explicit company, customer, original-invoice and product lookups preserve OCR references, localized descriptions, stored addresses and company rounding without requiring an accounting year (#75).

- Enable normalized customer invoice/credit-invoice list PDFs using explicit company rounding and company-wide headers for outer and nested reports. Preserve stored row details, totals, date filters and templates; support empty/no-year registers without legacy selections and avoid sorting caller-owned lists (#75).

- Enable normalized supplier invoice/credit-invoice journal PDFs with explicit company/year headers and voucher account/project/result-unit lookups for outer and nested reports. Preserve stored tax/rounding totals, existing journal metadata, templates and export-before-booking behavior (#75).

- Enable normalized invoice/credit-invoice journal PDFs with explicit company rounding, headers and account/dimension lookups. Add optional `credit-invoice journal --output`/`--overwrite` for both storage formats; preserve existing no-output preview/commit behavior and export-before-booking protection. Fix normalized credit creation to defer singleton-backed voucher generation until journal planning (#75).

- Enable normalized format 3 inpayment/outpayment journal PDFs for preview and commit using explicit company/year headers and voucher-row account/project/result-unit lookups. Preserve journal planning, totals, posting order, templates and legacy constructors; failed PDF export still prevents commit (#75).

- Enable normalized format 3 `inpayment pdf-list` and `outpayment pdf-list` exports with explicit company/year and payment snapshots for outer and nested reports. Preserve currency-rate snapshots, nullable row values, totals, sorting and existing templates without mutating caller-owned lists or using legacy invoice caches (#75).

- Enable normalized format 3 product-register PDF and XLSX exports with read-only all-time stock projections across invoices/credits, orders, purchase orders, inventory and deliveries. Preserve stock flags, unlinked-order rules, integer quantities and one-level parcel expansion; explicit company/parcel headers and lookups avoid singleton caches (#75).

- Enable normalized format 3 `customer list --output` and `supplier list --output` PDFs with explicit company headers and copied register lists, including companies without accounting years. Retain legacy fields, sorting, templates and GUI constructors (#75).

- Enable normalized format 3 CLI `accounts-payable` and `supplier-debts` PDFs with explicit company/year, supplier, invoice, credit and outpayment inputs. Preserve stored tax/rounding amounts, inclusive cutoff rules and report-specific balance precision; payable subreports no longer require singleton headers or supplier caches (#75).

- Enable normalized format 3 CLI `accounts-receivable` and `customer-claims` PDFs using explicit company/year, customer, invoice, credit and payment inputs. Preserve cutoff inclusion, cash exclusion, tax/company rounding and report-specific balance precision; nested receivable rows no longer depend on singleton headers or global customer caches (#75).

- Enable CLI `sale-values` PDFs on normalized format 3 storage through a read-only company-scoped sale-row projection. Preserve legacy full-month buckets, discounted amounts, currency conversion, credit subtraction and summary totals without singleton lookups or partially hydrated editable documents (#75).

- Enable CLI `sales-report` PDFs for normalized format 3 databases using explicit company/year, products, invoices, credits and supplier invoices. Preserve historical purchase-cost, parcel, credit-currency and sorting behavior without singleton lookups or in-place register sorting; keep legacy GUI paths unchanged (#75).

- Add an independent Swedish Flarum 2 forum container using the checksum-pinned official installation archive, SQLite, and documented deployment/backup workflows.
- Enable GD WebP encoding required by Flarum logo uploads and cover the authenticated upload route in container tests.
- Version the forum's custom CSS, website header and GoatCounter footer with manual application/rollback instructions; no automatic settings changes.
- Keep the discussion-list pane hidden when closed and correctly align it with the centered forum frame and both headers when open or pinned after scrolling.
- Route CI by application/container/theme inputs, remove duplicate forum feature-branch builds, add lightweight theme tests, and cancel superseded PR runs while preserving release/manual execution.

### Added

- Product-revenue PDFs now support normalized storage (data format 3), with explicit company/year and invoice/credit inputs for both outer and monthly printers. Discounted row amounts, document currency rates, credits and date filtering are preserved without live product lookups. CLI register ordering is consistent across storage formats; legacy GUI constructors are unchanged. The existing partial-month display behavior remains tracked in #158.
- Customer- and supplier-revenue PDFs now support normalized storage (data format 3), using explicit company/year headers, invoice/credit snapshots and monthly subreport inputs. Existing currency, discount and credit calculations are preserved. Both CLI backends now order register rows consistently by business number; GUI constructors retain their existing behavior. The pre-existing partial-month visibility issue is tracked separately in #158.
- VAT report and VAT control PDF exports now support normalized storage (data format 3), including the VAT report's nested subreport. Explicit company/year headers and account aggregation avoid the legacy singleton while preserving VAT boxes, rounding and control start-index/fallback semantics. Legacy GUI printers and service-based VAT settlement behavior are unchanged.
- Income-statement PDF export now supports normalized storage (data format 3), using explicit company-scoped years, dimensions and the selected budget. Existing period/year, previous-year and budget report calculations are preserved without singleton-backed row resolution.
- Balance-sheet and general-ledger PDF exports now support normalized storage (data format 3). Report headers use the explicitly selected company and year's maximum voucher number, and report calculations use hydrated accounting data without starting or selecting the legacy SSDB singleton. Existing legacy constructors remain compatible.

### Fixed
- Size all customer-invoice dialogs from their populated layout and available monitor work area instead of forcing 800×600. Keep Save/Cancel outside a scrollable form so they remain accessible on small displays and with larger fonts.
- Preserve the first character when typing starts account or numeric cell editing on macOS; Escape dismisses suggestions before closing their dialog.
- Refresh voucher and customer-invoice caches immediately after a successful save/edit/delete rather than depending on the background SQL trigger queue; keep duplicate invoice notifications idempotent and refresh the affected windows on the Swing event thread.
- Keep keyboard focus in searchable dropdown fields when account and other suggestion lists open, including on macOS. Preserve mouse selection, scrolling, dropdown buttons and add/edit/delete menus; add cross-platform GUI regression tests and opt-in, metadata-only focus diagnostics with a legacy comparison mode.

### Changed
- Normalized storage now supports supplier invoice and credit-invoice list PDF exports with explicit company headers and stored row snapshots; preserves supplier tax/rounding totals, local-currency conversion, and legacy GUI constructors.

- Fixed the SIE import voucher-number existence check: an empty `Optional` no longer counts
  as an existing voucher. Unused source numbers (including mapped series numbers) are preserved;
  duplicate or missing numbers receive the next number on both storage backends.
- Full-replacement SIE CLI import (types 1, 2, 3, and 4) now supports normalized storage
  (data format 3). Complete detached parsing precedes atomic replacement of the selected year's
  accounts, balances, monthly budgets, and vouchers, together with company dimension reconciliation.
  Year/company identities and other-year accounting data are preserved. Referenced dimension
  removals fail safely; retained keys are updated without breaking typed references. Source files
  remain immutable and duplicate overrides remain explicit. Import history follows database commit;
  it is not a cross-filesystem/database transaction.
- Missing voucher numbers in non-A SIE series now receive the next number without null arithmetic.
  Previous-year SIE results use the exporter's explicit context rather than the legacy singleton.
- SIE CLI export (types 1, 2, 3, and 4E) now supports normalized storage (data format 3),
  using explicit company/year data, previous-year balances, monthly budgets, and dimension
  registers. SIE-only rounding rows retain their approval requirement and never alter stored
  vouchers. Type 4 `--vouchers-only` imports support normalized storage with complete parsing
  before atomic batch persistence,
  explicit dimension resolution, and existing import-history/source-immutability safeguards.
- VAT CLI reports and settlement preview/commit now support normalized storage (data format 3),
  preserving legacy VAT-box truncation, settlement rounding, and voucher numbering. VAT report
  and control PDF exports fail explicitly with `NORMALIZED_STORAGE_UNSUPPORTED` until their
  singleton-dependent printers are ported.

### Added

- Add the migration foundation for ordered, SHA-256-verified SQL schema history and deterministic
  conversion of legacy Swedish local timestamps to UTC instants. It is not yet connected to normal
  startup and does not change or migrate the active database schema.
- Add typed, length-prefixed semantic fingerprint primitives with per-domain counts and accounting
  totals, plus read-only detection of abandoned normalized-migration directories. No active database
  data or startup behavior changes yet.
- Add the first versioned normalized accounting-core staging schema for companies, years, accounts,
  balances, budgets, vouchers, and voucher rows, with explicit SQL types and enforced relationships.
  It remains isolated from startup and active user databases.
- Add the first legacy-to-normalized accounting-core converter with independent source/destination
  fingerprints, exact accounting controls, correction links, and reported Swedish DST resolutions.
  Conversion still targets only a caller-provided staging database.
- Add file-backed normalized staging-catalog creation with clean shutdown, durable reopen and a second
  fingerprint comparison. The candidate remains separate from the active database and is never activated.
- Normalize shared currencies, units, payment terms, delivery terms, and delivery ways in staging and
  include them in source/destination fingerprints, including lookup values referenced only by companies.
- Flatten company contact, banking, tax, address, mail, standard-text, default-account, and numbering
  settings into explicit staging columns/tables with independent source/destination fingerprints.
- Normalize company-scoped projects and result units in staging, preserving nullable text, concluded
  state/date, and rejecting disagreement between legacy table keys and serialized object identifiers.
- Normalize voucher templates and automatic distributions with ordered child rows, exact decimal
  values, dimension references, legacy Swedish modification times, and table/object key validation.
- Normalize the complete customer register and invoice/delivery addresses in staging, including
  embedded currencies and terms, exact limits/discounts, flags, null/empty text, and key validation.
- Normalize the complete supplier register and address in staging, including supplier-only currency
  and term lookups, payout metadata, null/empty text, and scalar/object number validation.
- Normalize products, localized descriptions, account overrides, and ordered parcel components in
  staging, preserving raw prices/weight/volume and company-scoped dimension references.
- Add the normalized customer-invoice staging schema and converter for sale snapshots, addresses,
  account overrides, ordered rows, invoice state, and embedded voucher snapshots, with durable fingerprints.
- Add the matching normalized customer-credit-invoice staging schema, including credited-invoice number,
  sale/address/account/row snapshots and embedded voucher graph.
- Convert complete legacy customer credit invoices into staging with source/target/durable fingerprints,
  preserving all snapshot fields, ordered rows, embedded vouchers, and Swedish DST decisions.
- Add the normalized periodic-invoice staging schema for templates, generated instances, added flags,
  and ordered rows.
- Convert complete legacy periodic invoices into staging with template, generated instances, added
  flags, and ordered rows, verified by source/target/durable fingerprints.
- Normalize customer inpayments and supplier outpayments in staging with headers, ordered payment rows,
  default accounts, and main/difference voucher snapshots, verified by fingerprints.
- Normalize supplier invoices in staging with ordered rows, account overrides, and main/correction
  voucher snapshots, preserving exact tax/rounding sums and BGC/entered/stock flags.
- Normalize supplier credit invoices in staging with the credited-invoice snapshot number, ordered rows,
  account overrides, and main/correction voucher snapshots, verified by durable fingerprints.
- Normalize customer orders and tenders in staging with complete sale snapshots, addresses, default
  accounts, ordered rows, and document links (invoice/periodic-invoice/purchase-order numbers, expiry),
  verified by durable fingerprints.
- Normalize purchase orders in staging with supplier snapshot, delivery/supplier addresses, ordered
  rows with supplier article numbers, default accounts, and linked invoice snapshot number.
- Normalize in/out deliveries and inventory counts in staging with headers and ordered product/quantity
  rows, preserving stock quantity, change, dates and text, verified by durable fingerprints.
- Normalize own reports in staging with headings, account-row snapshots, and monthly budgets, completing
  explicit representation of every legacy `OTHER` domain table.
- Add an end-to-end migration of the real v1.0.1 database fixture: engine upgrade, all converters,
  durable staging reopen, matching fingerprints, and a reopened-source fingerprint proving the legacy
  catalog's accounting data is unchanged.
- Add the normalized activation service: verified physical rollback archive, retained source, atomic
  staging swap with rollback on failure, and recovery-state detection; end-to-end test activates a real
  converted fixture and reopens a functional normalized catalog with no `OTHER` columns.
- Add a normalized accounting-core reader that rebuilds companies, years, account plans, accounts, and
  vouchers from explicit columns, with a parity test proving the real v1.0.1 fixture reads identically
  from legacy and normalized representations.
- Add a normalized accounting-core writer with transactional add/update/delete for companies, years,
  plans/accounts, and vouchers with ordered rows, including dependency-ordered company deletion and
  legacy Swedish edit-time conversion.
- Add an SSDB-shaped normalized accounting store facade (current company/year selection, core CRUD,
  caller-owned transactions) and normalized register reads/writes for customers, suppliers, and products.
- Add the opt-in normalized migration service that composes engine upgrade, staging conversion,
  fingerprint verification, activation with rollback artifacts, and data format 3 recording. It is
  explicit and not part of startup; inspection now reports known normalized formats without rejection.
- Add `bokfri database normalize` as an explicit, one-way CLI entry to the normalized migration
  pipeline, with JSON output, idempotent no-op on an already-normalized database, and verified
  backup/retained-source artifacts.
- Add the runtime storage dispatch: the CLI runtime selects legacy SSDB or the normalized stores
  by the recorded data format, so a normalized (format 3) database opens without starting legacy
  object storage. Ported read commands (`status`, `company list/use`, `year list/use`,
  `account list`) work on normalized storage; unported commands fail fast with a clear
  `NORMALIZED_STORAGE_UNSUPPORTED` error instead of touching legacy tables.
- Port the voucher write path and register read paths to normalized storage: `voucher list/create`
  and `customer/supplier/product list` work on a normalized database through runtime dispatch
  helpers, and the register store reads the shared currency/unit/payment-term lookups plus
  company-scoped projects and result units.
- Extend the normalized register store to full fidelity: customers with invoice/delivery
  addresses, suppliers with address and outpayment number, and products with tax code, unit,
  project/result-unit, locale descriptions, default accounts, and component rows round-trip
  through explicit columns, with deletes cascading over child rows. Normalized company
  read/write now carries VAT rates, `customer/supplier/product create` work on normalized
  storage, and opening a normalized runtime clears stale legacy singleton selection so
  domain-object constructors never observe it.
- Add the normalized customer invoice store with full fidelity (header, addresses, default
  accounts, ordered rows, booked voucher snapshot) plus legacy-compatible invoice numbering
  (auto-increment counter floor) and credit/inpayment sums for balances. Port
  `invoice list/show/create/validate` to normalized storage. Sale totals and rounding can now
  be computed without the global SSDB singleton via explicit rounding-flag overloads, and the
  normalized company read/write carries the rounding flag.
- Extend the normalized invoice store to credit invoices (crediting number, same child tables)
  and add the normalized inpayment store with rows, default accounts, and main/difference
  voucher snapshots; numbering mirrors the legacy auto-increment counter floors. Port
  `credit-invoice list/show/create/validate` and `inpayment list/show/create/validate` to
  normalized storage, with balance-aware validation supplied by the runtime instead of the
  global singleton.
- Add the normalized supplier invoice store (supplier invoices and supplier credit invoices
  with rows, default accounts, and main/correction voucher snapshots) and outpayment store,
  with legacy counter-floor numbering and credit/outpayment sums for balances. Port
  `supplier-invoice list/show/create/validate`, `supplier-credit-invoice list/show/create/validate`,
  and `outpayment list/show/create/validate` to normalized storage. `SSSupplierInvoice.setSupplier`
  now copies the supplier's stored currency reference instead of resolving it through the
  global SSDB singleton.
- Port invoice booking to normalized storage: `SSInvoice.generateVoucher` gained a
  collaborator-supplied overload (account plan, rounding flag, projects, result units), the
  normalized invoice store marks invoices entered and replaces their voucher snapshot, and the
  register store carries company auto-increment counters with legacy read/bump semantics.
  `invoice journal` preview and commit work on a normalized database. Normalized reads now
  initialize empty default-account maps so booking never trips over null maps.
- Port all remaining booking journals to normalized storage: credit-invoice, inpayment,
  outpayment, supplier-invoice, and supplier-credit-invoice journal preview/commit. All
  `generateVoucher` variants (credit invoice, inpayment, outpayment, supplier invoice,
  supplier credit invoice) gained collaborator-supplied overloads, and every store can mark
  its documents entered with voucher snapshot replacement. Payment rows are now built field
  by field in the CLI so the static legacy saldo map is never consulted.

### Fixed

- Normalize fingerprint decimal comparison to the staging storage contract (30 fractional digits,
  half-up) so legacy values stored with floating-point-expanded precision compare equal to what the
  explicit columns actually store. Preserve standard texts whose value is null.

### Changed

- Document the staged migration design for replacing Java-serialized HSQLDB `OTHER` columns with
  explicit tables, constraints, checksummed schema history, rollback backups, and semantic verification.
  This design step does not change the active database format.
- Replace picocli with Apache Commons CLI, inspecting and binding only the selected command path at startup.
  All commands remain listed in root help; accounting operations and JSON response shapes are unchanged.

### CLI compatibility

- Remove automatic `@argument-file` expansion. Normal `--file` inputs are unchanged.
- Reject repeated value-bearing options across the entire command invocation, including repetitions with the same value.
  Repeated boolean switches remain allowed.
- Use tabular CLI help and report unknown options or extra arguments even when `--help` is present (exit 2).
  Valid help still works without otherwise-required inputs and exits 0.

## [1.2.0] - 2026-09-13

### Added
- CI now starts and paints the packaged Swing application on Linux, Windows, and macOS before
  publishing artifacts, catching platform-specific Look & Feel and runtime startup failures.
- The CLI and graphical interface now import and export all six structured `.xlsx` data types—account
  plans, customers, products, suppliers, vouchers, and voucher templates—through shared,
  GUI-independent fastexcel services. JExcelAPI and its Log4j 1.2 dependency have been removed.
- The untested custom XML customer/product exchange and E-butik.se customer import have been
  removed; modern order exchange is tracked separately.

### Fixed
- Linux keeps its traditional Plastic3D appearance on all desktop environments, without requesting
  the Windows-only JGoodies font policy that previously caused a startup failure.
- Windows Look & Feel fonts no longer shrink inversely with Display Scale; tables and compact input
  controls retain readable text and font-derived dimensions at 125%, 150%, and higher scaling.
- Report previews now render at the display's device-pixel scale while retaining their logical zoom,
  keeping text and lines sharp on HiDPI displays and when moving between differently scaled monitors.
- Historical invoice and credit-invoice rows without a stored VAT code are now treated as tax-free
  during balance calculation instead of preventing the application from starting.
- Sales documents with a missing 12% tax-rate value now use the standard default instead of
  failing during invoice printing.
- Unexpected failures from progress operations now use the copyable diagnostic dialog and are
  always written to the GUI log with their full stack trace.

### Changed
- HSQLDB has been upgraded from 1.8.0.10 to the 2.5.0 compatibility bridge. Legacy catalogs are
  upgraded on a staged copy after a verified rollback archive is created, while the original
  catalog is retained. Backup and cleanup flows now include HSQLDB's `.lobs` file.
- Report preview exports now use PDF as the single presentation and archive format. The legacy
  HTML, RTF, and layout-oriented XLS options have been removed; dedicated Excel workflows remain.
- The report engine now uses JasperReports 7.0.8 with reproducibly converted JRXML/JRTX sources;
  the complete PDF gallery remains pixel-identical to the JasperReports 6.21.5 baseline.
- Report rendering and PDF export now have a shared headless service boundary; invoice GUI preview,
  invoice CLI export, and balance-report rendering use the same incremental adapter pattern.
- Reports now use bundled, PDF-embedded DejaVu Sans fonts on every platform;
  OCR invoices use a separately licensed and embedded OCR-B font.
- Report templates now have one canonical source tree under Maven resources; the
  unused duplicate tree and checked-in compiled reports have been removed, and CI
  compiles every reachable JRXML template.
- The CLI can export customer-invoice journals and VAT reports as PDF with the
  same `--output` and `--overwrite` behavior as other report commands.
- Invoice PDF export supports the OCR variant, delivery notes with or without
  prices, and picking lists; the sales report is also available headlessly.
- The deterministic gallery covers English invoices and invoices with an embedded
  company logo; company JSON input accepts the existing optional logotype path.
- Customer inpayment journals and lists, accounts receivable, and customer-claim
  reports can now be exported headlessly and are covered before and after payment.
- Supplier invoice and credit-invoice journals, outpayment journals and lists,
  accounts payable, and supplier-debt reports can now be exported headlessly.
- Customer and supplier invoice registers, credit-invoice registers, and the
  supplier register can now be exported as PDF from their CLI list commands.
- Product lists, selected account plans, customer revenue, and product revenue
  can now be exported headlessly and are included in the deterministic gallery.
- VAT control, monthly sales values, and supplier revenue can now be exported
  headlessly and are covered by the deterministic gallery.
- The deterministic gallery covers empty and negative financial reports, wrapped
  long descriptions in the general ledger, and VAT reports with all domestic rates.
- Outstanding entered invoices can now be exported as deterministic payment
  reminders for an explicit reminder date. Reminder PDFs use maintainable shared
  styles, vertically flowing invoice blocks, and non-overlapping totals.
- Report fragments now preserve external Jasper style templates, and compiled
  report caches are invalidated when either their JRXML source or shared styles change.
- The deterministic PDF gallery now covers the invoice journal, its committed
  voucher, the voucher list, and the VAT report with semantic balance and VAT-box checks.
- BAS 2026 standard sales accounts 3001–3004 now carry the VAT report codes implied
  by their account names, so domestic sales reach the correct taxable-sales box.

## [1.1.1] - 2026-08-20

### Changed
- GUI logs now live below Bokfri's platform data directory, keeping release and development
  logs separate. The About dialog shows the log path and can open its folder or copy the path.
  Unexpected technical errors provide copyable diagnostics, full stack traces, and a direct
  link to the log folder while ordinary validation messages remain concise.
- GUI forms now keep their OK buttons actionable and explain missing or invalid
  input when saving, instead of silently disabling the action. Structured validators
  show all detected problems for customers, products, invoices, payments, and vouchers.
- Replaced the overlapping `paths`, `doctor`, `company current`, and `year current`
  CLI commands with one `bokfri status` overview that validates the selected company
  and accounting year and returns a failing exit code for incomplete or broken state.
- Recreating the currently selected demo company now selects its replacement and
  current accounting year instead of leaving a stale selection.
- Accounting years are sorted newest first in the GUI and CLI, and the selected
  company and year are marked consistently in CLI lists.
- `account list --filter TEXT` now filters the selected year's accounts by number
  or description using case-insensitive substring matching.
- `company list` and `year list` now mark the effective selection consistently with
  a compact `*` column; company JSON rows also expose `selected: true|false`.
- Human-readable CLI lists and financial reports now use shared content-sized columns,
  headings, and right-aligned numbers instead of inconsistent tab stops. JSON output
  remains unchanged for integrations.
- `bokfri status` now reports the effective CLI log path. Unexpected CLI failures write
  full diagnostics to `logs/bokfri-cli.log`, return exit code 70, and can print the same
  details with `--verbose`; expected command errors remain concise and script-friendly.
- Sales, delivery-note, and picking-list reports now accept decimal product quantities
  and preserve exact decimal arithmetic when calculating total weight and volume.
- CLI commands backed by GUI reports can now write the same Jasper reports as PDF:
  balance sheet, income statement, general ledger, voucher lists, individual vouchers,
  and credit invoices. `--format text|json` still controls stdout, while `--output`
  creates the PDF and `--overwrite` protects existing files.
- Installers now bundle a compressed, minimal Java runtime and stage only the production
  fat JAR for `jpackage`. Release artifacts are about 30–35% smaller than in 1.1.0.
- Removed the unused Jackson YAML dependency and its transitive SnakeYAML dependency.

## [1.1.0] - 2026-08-16

### Changed
- SIE import and export now keep decimal amounts out of binary floating-point conversions,
  and CLI monetary output consistently uses two decimals while quantities, rates, and other
  non-monetary decimals retain their actual precision.
- Carry-forward rounds new opening balances to two decimals and explicitly previews any
  largest-remainder öre adjustment needed to preserve balance. SIE export likewise detects
  two-decimal voucher differences and requires approval before adding disclosed, export-only
  rounding rows; stored historical vouchers are never changed.
- Customer invoice, credit-invoice, order, tender, and periodic-invoice rows now support
  decimal quantities such as `0.5` hours; stock-tracked products still require whole units.
- Bokfri data format 2 is now the sole write format. Opening format 1 requires explicit
  approval, creates and verifies a full backup, and then migrates before normal loading;
  CLI users inspect and migrate it explicitly with `database status` and `database migrate`.
- Voucher rows created with an account now retain its account number after persistence;
  demo vouchers therefore appear in balances and CLI output with correct totals and accounts.
- Voucher list/show text now includes both debit and credit totals plus account descriptions.
- Financial report commands now render their rows, totals, periods, and balances
  in text mode instead of only printing a generic “generated” confirmation.
- CLI text and logs now use the active terminal charset, and plain-text date ranges
  use an ASCII hyphen for reliable rendering in Windows consoles.
- Packaged CLI launchers now favor faster startup with `-XX:TieredStopAtLevel=1`;
  the long-running GUI launcher retains the JVM's normal optimization strategy.
- Database migration is now a dedicated `bokfri database migrate` operation instead
  of a global `--migrate` option inherited by every CLI command; `database status`
  reports the detected and supported formats without normal database startup.
- CLI and GUI now share the selected company and accounting year. The public
  context concept has been replaced by `company current|use` and `year current|use`;
  `--company-id`, `--year-id`, and `--data-dir` remain per-command overrides.
- The bundled demo company is now generated through Bokfri's Java services instead
  of serialized SQL, with completed 2025/26 and ongoing 2026/27 accounting years.
- Windows MSI packages now install `bokfri` (`bokfri-dev` for development builds)
  on the system PATH, use stable channel-specific upgrade identities, and verify
  command discovery plus exact PATH cleanup during uninstall in CI.
- Global CLI options now work before or after nested commands. VAT, trial balance,
  income statement, and general-ledger reports default to the full selected
  accounting year; balance sheet and account balance default to its final day.

### Added
- Added code-first JSON Schema Draft 2020-12 output through `schema` under every
  CLI command that accepts Bokfri JSON input. Schemas are generated at runtime
  from the same Jackson input classes and structural validation annotations used
  when reading input, require no database, and have stable versioned `$id` values.
- Added application-level database format metadata and startup compatibility checks;
  databases from newer unsupported Bokfri formats are rejected before normal loading.
- Added versioned JSON manifests to new full backups while retaining verification
  and restore support for legacy v1.0.1 backups without manifests.
- Added checked-in v1.0.1 database and backup fixtures plus integration tests for
  direct opening and backup verification, restoration, and opening.
- Added GUI and `bokfri demo recreate [--commit]` actions for safely replacing
  recognized current or legacy demo companies while preserving all other companies.
- Added privacy-friendly GoatCounter visitor statistics to the website and help pages.
- Added the first headless CLI slice with version, path, diagnostics, shared
  company/year selection, and read-only company and accounting-year commands.
- Added text and JSON output plus explicit per-command data, company, and year
  overrides for scripts, CI, and agent-driven automation.
- Added account and voucher inspection plus JSON-based manual voucher
  validation, dry runs, and creation through a shared UI-independent validation
  and application service.
- Added direct CLI routing: launching Bokfri without arguments opens the GUI,
  while `--help`, `context`, `voucher`, and other arguments invoke the CLI.
- Added packaged CLI launchers, including a Windows console launcher, and CI
  coverage for native launchers plus the finished Linux AppImage.
- Added Windows MSI acceptance coverage that silently installs the package,
  runs the complete CLI black-box scenario against the installed launcher,
  uninstalls it, and verifies that program files are removed without deleting
  Bokfri user data.
- Added opening-balance show/validate/set and balance-only carry-forward between
  accounting years, with preview by default and explicit commit.
- Added company creation, account-plan discovery, and accounting-year creation
  commands so automated scenarios can bootstrap their own bookkeeping context.
- Added headless VAT report calculation and explicit VAT settlement
  preview/commit using Bokfri's existing VAT codes and settlement accounts.
- Added supplier outpayment list/show, JSON validation and creation, and
  period-based outpayment journal preview/commit with balance checks.
- Added supplier-invoice list/show, JSON validation and creation, and
  period-based supplier-invoice journal preview/commit.
- Added supplier list/show and JSON validation, dry runs, and creation with
  company defaults and automatic unique outpayment numbering.
- Added customer inpayment list/show, JSON validation and creation, and
  period-based inpayment journal preview/commit with invoice balance checks.
- Added preview and commit support for period-based customer-invoice journals,
  preserving Bokfris existing model of one compressed voucher per journal.
- Added headless customer-invoice PDF export with language selection, safe
  overwrite handling, and cross-platform black-box verification of the actual
  PDF artifact.
- Added JSON-only product validation, dry runs, and creation with company
  defaults, explicit VAT/account references, and shared service validation for
  Swing and CLI.
- Added JSON-only customer-invoice validation, dry runs, and creation with
  existing customer/product references, inherited defaults, posting previews,
  and shared service validation for Swing and CLI.
- Added JSON-only customer validation, dry runs, and creation with optional
  schema version, company-derived defaults, structured validation errors, and
  text output by default with explicit JSON output for automation.
- Added read-only customer, product, and customer-invoice list/show commands,
  including invoice totals, status, and posting source rows in JSON output.
- Added a cross-platform CI black-box test that launches the assembled fat JAR
  and verifies shared selections, JSON contracts, account/year lookup, voucher
  validation, dry runs, creation, list/show round trips, and stable errors.
- Expanded the black-box scenario into an isolated full accounting year: it now
  creates its own company and 2026 year, runs opening balances, sales,
  purchases, payments, VAT and manual vouchers there, then creates 2027 and
  verifies carry-forward into its opening balance.
- Added CLI full-backup creation, history listing, archive verification, and
  preview-first restore to a selected data directory with protected replacement.
- Added headless SIE export for the selected company and accounting year in SIE
  types 1, 2, 3, and 4E, with IBM-437 encoding and safe output handling.
- Added CLI trial balance, balance sheet, income statement, general ledger, and
  point-in-time account balance reports with JSON rows and financial control totals.
- Added supplier credit-invoice CLI commands for validation, dry-run creation,
  partial or full crediting, inspection, and journal booking.
- Added customer credit-invoice CLI commands for validation, dry-run creation,
  partial or full crediting of booked invoices, inspection, and journal booking.
- Added CLI SIE import with non-mutating preview by default, explicit commit,
  full-year or voucher-only modes, and SHA-256 import history stored separately
  from the immutable source file. GUI imports now use the same immutable-source
  behavior and shared import history.

### Fixed
- `-h` and `--help` now work consistently on every CLI command and nested
  subcommand, without requiring the command's normal positional arguments or options.
- Expanded packaged CLI regression coverage for missing selections, out-of-year
  reports, unbalanced vouchers, invalid credits, and non-mutating previews.
- Prevented customer invoices from being created or edited without a valid sales
  account from the selected accounting year, in both Swing and the CLI.
- Initialized standard accounts for companies created through the CLI, matching
  GUI-created companies so journals, payments and VAT work without demo data.

## [1.0.1] - 2026-08-01

### Added
- Added practical getting-started guides for choosing an account plan, creating
  a company and accounting year, entering opening balances, and recording the
  first voucher.
- Added invoice rendering tests covering long invoices and OCR payment details.
- Added this documented release procedure for future Bokfri releases.

### Changed
- Replaced deprecated `Locale` constructors and string-based process execution
  with their modern Java equivalents.
- Moved the minimum Maven version check to Maven Enforcer and removed an
  unsupported assembly-plugin option, eliminating build-configuration warnings.
- Completed generic typing of the remaining Swing models, renderers, editors,
  selection listeners, and report data sources so the Java build compiles
  without unchecked-operation warnings.
- Marked the few unavoidable type-erasure boundaries explicitly instead of
  hiding unrelated unchecked operations.

- Updated new-company defaults for BAS 2026, including input VAT account 2641,
  VAT settlement accounts 1650 and 2650, and the standard bank account 1930.
- Simplified invoice layouts by removing obsolete QR payment fields and keeping
  totals below all line items, including on multi-page invoices.
- Improved OCR payment details with clearer Swedish wording and placement.

### Fixed
- Restored loading of legacy XML menu definitions.
- Restored loading of voucher templates created before the `java.time`
  migration.
- Replaced an existing voucher template with the same name instead of creating
  duplicates, with a confirmation prompt before replacement.
- Corrected VAT settlement voucher generation so BAS 2026 uses accounts 1650
  and 2650, a missing account selection cannot silently become account 1010,
  and VAT balances are summed before whole-krona rounding.
- Normalized legacy floating-point noise in entered amounts before VAT
  settlement rounding, preventing false one-krona adjustments.
- Removed stale `java.util.Date` expectations from report headers, payment
  journals, reminder rows, and VAT report/dialog flows; report dates are now
  supplied and formatted directly from `java.time` values.
- Changed the fallback account for input VAT on supplier invoices from the
  group account 2640 to the standard posting account 2641. Explicitly selected
  company defaults remain unchanged.

## [1.0.0] - 2026-07-27

### Added
- Bokfri branding, package metadata, application icons, and a portable SVG
  logo across the app and project website.
- A responsive Bokfri website at `https://bokfri.viblo.se/` with product
  information, platform-specific download links, contact information, and
  GitHub Pages deployment.
- Browser-based online help with search, section navigation, breadcrumbs, and
  previous/next links, generated from the application's Swedish help content.
- Six bundled BAS 2026 account plans for Swedish companies, associations, and
  sole traders, including a reduced K1 plan with report codes.
- A checksum-pinned standalone account-plan generator and review reports for
  reproducible annual BAS, VAT, and SRU updates.
- Integration tests that import every bundled BAS 2026 plan, verify repeat
  imports are idempotent, and check selected VAT, SRU, and report mappings.
- Git commit metadata in the displayed application version to identify the
  exact development build in support and bug reports.
- Modernization plan (`MODERNIZATION.md`) documenting a phased approach to
  bring the codebase from Java 5/6-era style to modern Java.
- `AGENTS.md` with build, test, lint commands and code style guidelines for
  AI-assisted development.
- Checkstyle configuration (`checkstyle.xml`) enforcing project code style
  guidelines (Phase 7 Step 34).
- SpotBugs static analysis replacing abandoned FindBugs (Phase 7 Step 35).
- JaCoCo code coverage reporting with 5.3% baseline (Phase 7 Step 36).
- CI quality gates: Checkstyle and coverage report upload on PRs (Phase 7
  Step 37).
- GitHub Actions CI/CD workflow (`ci.yml`):
  - Pull request builds and tests on Ubuntu, Windows, and macOS with JDK 21.
  - Development and tagged builds on `main` for Linux, Windows, and macOS.
  - Native package creation via jpackage (AppImage, MSI, DMG).
  - Upload of platform packages as workflow artifacts.
- AppImage build support for Linux distribution.
- JUnit 5 test foundation with Maven Surefire integration, test infrastructure
  utilities (`TestDBHelper`, `TestLauncher`), and initial core tests for
  `SSNewCompany`, `SSDB`, and `SSVoucher` (PR #3).
- Core business logic tests for `SSAccountPlan`, `SSNewAccountingYear`,
  `SSVoucherMath`, and `SSBudget` (PR #5).
- Database integration tests for SSDB CRUD operations covering invoices,
  suppliers, customers, products, and vouchers (PR #6, #7).
- `SSDateUtil` adapter class bridging `java.util.Date` and `java.time`
  (Phase 3 Step 15) (PR #9).

### Changed
- Started Bokfri's independent version history at 1.0.0, separate from the
  inherited JFS Accounting version sequence.
- Replaced the legacy bundled account-plan selection with six current BAS 2026
  plans while retaining reviewed Bokfri VAT mappings where unambiguous.
- Moved general help from the embedded JavaHelp window to the system browser
  and linked it to the maintained online documentation.
- Updated the Help menu, support, release, and About links to the Bokfri
  website and GitHub project.
- Reworked the published Swedish help for UTF-8, responsive mobile navigation,
  clearer section names, corrected SIE labels, and consistent Bokfri styling.
- Migrated the active CI and Pages deployment from Codeberg/Forgejo to GitHub,
  with `main` as the development branch and `bokfri.viblo.se` as the custom
  website domain.
- Cleaned up Maven dependency analysis by declaring the activation API used by
  mail attachments, splitting JUnit 5 test API/runtime dependencies, and
  documenting runtime-only analyzer ignores for Logback and the JUnit engine.
- Polished Help menu wording: `Rapportera problem...` and
  `Hämta senaste version...` better describe the linked GitHub pages.
- Isolated `Bokfri Dev` packaged builds to a separate `bokfri-dev` user
  data/config directory so dev builds no longer share storage with release builds.
- Package main-branch Linux AppImage and macOS DMG builds as `Bokfri Dev`,
  matching the Windows MSI dev-channel naming for side-by-side tester installs.
- Restored backup create/restore compatibility after the `SSBackup` metadata
  date migration by making serialized `backup.info` and `backup.history`
  files readable across both legacy `Date` and `LocalDateTime` formats.
- Modernized Java syntax (Phase 1): replaced anonymous inner classes with
  lambdas, added diamond operator, converted loops to streams, adopted
  try-with-resources for I/O (PR #4).
- Replaced `System.out`/`System.err`/`printStackTrace` calls with SLF4J
  logging backed by Logback (Phase 2) (PR #8).
- Updated `MODERNIZATION.md` to reflect current progress through Phase 3.5
  (PR #13).
- Migrated domain model date fields from `java.util.Date` to `LocalDate`
  (Phase 3 Step 16) (PR #14).
- Replaced `SimpleDateFormat` usage with `DateTimeFormatter` throughout the
  codebase (Phase 3 Step 17) (PR #15).
- Eliminated all `java.util.Calendar` usage from the codebase, migrating GUI
  date components, print reports, table renderers, calc utilities, and data
  classes to `java.time.LocalDate`/`ChronoUnit` (Phase 3 Step 18).
- Continued the date migration in GUI workflows by replacing more
  `new Date()` defaults with `SSDateUtil.today()` and `LocalDate` setters in
  invoice, order, purchase order, periodic invoice, tender, and credit invoice
  dialogs plus related invoice date chooser/table logic.
- Continued the date migration across calculations, import/export, backup,
  voucher editing, and report cache code so production `new Date()` runtime
  calls are eliminated in favor of `SSDateUtil` and `java.time` comparisons.
- Continued the date API cleanup by switching `SSDateMath`, `SSMonth`,
  `SSVoucher`, `SSInvoice`, and `SSSupplierInvoice` workflows and focused tests
  to prefer `LocalDate` accessors over deprecated `Date` bridges.
- Continued the date migration in payment, credit-note, and periodic-invoice
  calculations by replacing more legacy `Date` comparisons with `LocalDate`
  logic in in/out-payment math and period boundary handling.
- Continued the date migration in revenue, receivable/payable, budget, and
  value report flows by replacing more report-period comparisons and month
  splitting logic with `LocalDate`-based boundaries.
- Continued the date migration in stock-related math by replacing remaining
  purchase order, inventory, and in/out-delivery period checks with
  `LocalDate`-based comparisons.
- Continued the date migration in accounting-year and report setup flows by
  preferring `LocalDate` year boundaries and converting back to `Date` only at
  dialog and Jasper parameter boundaries.
- Continued the date migration in payment, inventory, and periodic-invoice UI
  panels by preferring `LocalDate` chooser accessors and only bridging back to
  `Date` for legacy stock-update and table-rendering APIs.
- Continued the date migration in company, customer, supplier, product,
  project, and result-unit monthly aggregates by switching more month-membership
  checks from deprecated `Date` accessors to `LocalDate` values.
- Continued the date migration in product pricing, inpayment lookup, and main
  book calculations by comparing `LocalDate` values directly and only bridging
  back to `Date` for legacy method contracts.
- Continued the date migration in periodic-invoice generation and pending
  invoice flows by keeping schedule calculations and next-invoice dates as
  `LocalDate` values internally.
- Continued the date migration in invoice due-date table and sales print flows
  by using `LocalDate` accessors directly and only converting to `Date` at
  report and table boundaries.
- Continued the date migration in list, journal, and debt printers by reading
  local date accessors directly and only bridging to `Date` for final display
  formatting.
- Continued the date migration in import flows by storing parsed BGMax,
  supplier-payment, voucher-import, and SIE voucher dates through `LocalDate`
  setters instead of deprecated `Date` setters.
- Continued the date migration in in- and out-delivery domain, table, panel,
  and list-printer flows by adding `LocalDate` accessors and removing immediate
  `Date` bridge round-trips.
- Continued the date migration in order, tender, purchase-order, and inventory
  report/import flows by using `LocalDate` accessors directly and limiting
  `Date` bridges to XML and Jasper boundaries.
- Continued the date migration in payment journal, reminder, main-book, and
  transaction-cleanup flows by reading local dates directly and only bridging
  to `Date` where report rendering still requires it.
- Continued the date migration in supplier-payment export flows by reading
  `LocalDate` values directly from payment models and only bridging back to
  `Date` for persisted config values.
- Continued the date migration in supplier-payment LB export posts by taking
  `LocalDate` values from payment models and only bridging to `Date` at the
  file-format boundary.
- Continued the date migration in Excel voucher export by letting writable row
  helpers accept `LocalDate` values directly instead of formatting through
  deprecated voucher `Date` accessors.
- Continued the date migration in app dialogs by exposing `LocalDate` values
  directly where menu flows immediately convert legacy `Date` selections back
  into local dates for processing.
- Continued the date migration in report dialogs by exposing `LocalDate`
  values directly for single-date reports and reading local date ranges
  directly from chooser widgets in list dialogs.
- Continued the date migration in table and report printer flows by keeping
  voucher, budget, value, sale-report, and starting-amount periods as
  `LocalDate`/`LocalDateTime` values until final dialog or Jasper boundaries.
- Continued the date migration in receivable, payable, debt, claim, and stock
  value printers by keeping report cutoff dates as `LocalDate` internally.
- Continued the date migration in main-book, balance, simple-statement, and VAT
  printers by keeping report periods as `LocalDate` through calculation.
- Continued the date migration in journal printers by passing `LocalDate` period
  values through report construction.
- Continued the date migration in stock account and inventory-basis printers by
  keeping selected report dates as `LocalDate` until stock calculation boundaries.
- Continued the date migration in result printers by storing report periods as
  `LocalDate` through result calculation.
- Continued the date migration in revenue printers by storing report periods as
  `LocalDate` through monthly distribution calculations.
- Continued the date migration in own-report printing by storing selected report
  periods as `LocalDate` through calculation.
- Continued the date migration in the quarter report by storing selected report
  periods as `LocalDate` through calculation and formatting.
- Removed the legacy stock update `Date` adapter after stock reports moved to
  `LocalDate` cutoffs.
- Continued the date migration in accounting-year tables by exposing year
  boundaries as `LocalDate` values instead of SQL `Date` display adapters.
- Removed a leftover voucher-row `Date` renderer registration now that edited
  timestamps use the `LocalDateTime` renderer.
- Removed the unused voucher editor `Date` renderer helper after voucher row
  setup moved to `LocalDateTime` rendering.
- Removed stale supplier and periodic invoice panel `Date` imports after those
  panels moved to local date chooser accessors.
- Removed the global table editor `Date` renderer/editor registration now that
  table date columns use `LocalDate` or `LocalDateTime` column classes.
- Continued the date migration in stock report dialogs by exposing selected
  cutoff dates as `LocalDate` and removing immediate report-caller adapters.
- Removed stale `Date` imports from list report dialogs that already filter on
  chooser `LocalDate` values directly.
- Removed obsolete stock report dialog `Date` accessors after callers moved to
  `LocalDate` accessors.
- Continued the date migration in cutoff report dialogs by returning selected
  dates as `LocalDate` for receivable, payable, claim, and supplier debt reports.
- Continued the date migration in project and result-unit result setup dialogs
  by passing report periods as `LocalDate` values directly.
- Continued the date migration in period-selection dialogs by exposing selected
  periods as `LocalDate` for balance, budget, VAT, statement, and value reports.
- Continued the date migration in the quarter report dialog by returning the
  selected quarter bounds as `LocalDate` values directly.
- Continued the date migration in the sale report dialog by exposing the
  selected report period as `LocalDate` values directly.
- Continued the date migration in the main book dialog by passing the selected
  report period as `LocalDate` values directly.
- Continued the date migration in the result report setup panel by passing the
  selected report period as `LocalDate` values directly.
- Continued the date migration in the legacy VAT report dialog by passing the
  selected report period as `LocalDate` values directly.
- Continued the date migration in the outpayment list dialog by exposing the
  selected report period as `LocalDate` values directly.
- Dropped the legacy pre-HSQL `bookkeeper.db` import path and its archived
  `db/databas_v1.zip` handoff, requiring very old installations to migrate via
  historical Bokfri releases before using this fork.
- Encapsulated 53 public mutable fields across 7 classes with proper
  getters/setters (Phase 4 Step 19).
- Introduced `Optional<T>` for ~100 public API methods across SSDB lookups,
  calc/math search methods, data model getters, and parser/decoder methods;
  reduced `return null` sites from ~419 to ~212 (Phase 4 Step 20).

### Fixed
- Fixed online help encoding, image paths, duplicate search entries, stale
  references, sidebar behavior, breadcrumbs, and previous/next navigation.
- Fixed the website header on small screens and removed platform font
  dependency from the SVG logo.
- Removed the obsolete year 2007 from the VAT report title.
- Improved startup failure feedback and corrected Help/About window icons and
  links.
- Increased the About dialog text area and gave the Help window the normal
  Bokfri application icon set.
- Fixed packaged report previews using unwritable installation/current-working
  directories for generated report caches, QR images, and email PDFs.
- Fixed stale compiled Jasper report caches surviving app upgrades by tying
  cached reports to the exact Bokfri build that produced them.
- Fixed sales and quarter report previews on systems without Arial installed by
  switching report title fonts to the JVM-portable `SansSerif` family.
- Fixed report previews rendering as blank after date modernization by keeping
  the shared Jasper `reportdate` parameter as a `java.util.Date` at the report boundary.
- Fixed combined Jasper report rendering by preserving print metadata when
  joining child reports, preventing blank preview/print/export pages.
- CI: use `target/dist` for AppImage build output.
- CI: use bash shell for Maven build and fix installer test paths.
- CI: install jpackage dependencies on Linux runner.
- CI: upgrade deprecated GitHub Actions from v3 to v4 (PR #2).
- Excluded build artifacts from git tracking.
- CI: fail `build_and_publish` job when tests fail (PR #10).
- Resolved database path issue: use per-user directories on Windows and
  macOS instead of hard-coded paths (PR #11).
- Caught `NullPointerException` in voucher comparator, fixed PR CI coverage
  reporting, and fixed Linux resource loading paths (PR #12).
- Added null guards in `SSTriggerHandler.triggerAction` and improved
  background-thread error detection in tests (PR #16).
- Fixed buggy delayed-days calculation in `SSReminderPrinter.getNumDelayedDays()`
  and `SSInvoiceMath.getNumDelayedDays()`: replaced epoch-based Calendar
  arithmetic with `ChronoUnit.DAYS.between()`.
- Fixed thread-safety issues: removed shared mutable `static Calendar` fields
  in `SSVoucherMath` and `SSBudget`.

### Removed
- The unused contextual-help API and obsolete embedded Help browser code after
  general help moved online.
- Obsolete network/server-mode menu and help entries, legacy menu actions, and
  the unsupported clear-transactions feature.
- Direct Xerces dependency by migrating XML parsing and serialization code to
  standard JDK XML APIs.
- Unused Spring dependencies by converting JasperReports custom OCR font
  registration from Spring bean XML to SimpleFontExtensionsRegistryFactory.
- Unused direct dependencies: legacy iText, JasperReports bundled fonts,
  Mockito, and direct `xml-apis`; PDF export now relies on JasperReports'
  OpenPDF dependency and XML APIs continue through JDK boundaries.
- Dead multi-user/server mode code (Phase 3.5): removed `SSPostLock`,
  `SSCompanyLock`, `SSYearLock`, and all lock acquisition/release calls
  across 54+ GUI files. Simplified `SSTriggerHandler` to a direct
  `Trigger.fire()` call. Reduced `SSDB` by ~1,300 lines (PR #17).
- Duplicate legacy entry point `SSBookkeeping.java` and 5 orphaned test data
  files (Phase 4 Step 21). Resolved all TODOs and converted remaining
  `System.out.printf` calls to SLF4J logging.
