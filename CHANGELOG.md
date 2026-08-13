# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Bokfri starts its own version history at 1.0.0. The project is a fork of 
[Fribok](https://fribok.org/) and 
[JFS Accounting](https://sourceforge.net/projects/jfsaccounting/),
diverging from upstream Fribok after version 2.2-SNAPSHOT.

## [Unreleased]

### Changed
- Voucher rows created with an account now retain its account number after persistence;
  demo vouchers therefore appear in balances and CLI output with correct totals and accounts.
- Voucher list/show text now includes both debit and credit totals plus account descriptions.
- Financial report commands now render their rows, totals, periods, and balances
  in text mode instead of only printing a generic “generated” confirmation.
- CLI text and logs now use the active terminal charset, and plain-text date ranges
  use an ASCII hyphen for reliable rendering in Windows consoles.
- Packaged CLI launchers now favor faster startup with `-XX:TieredStopAtLevel=1`;
  the long-running GUI launcher retains the JVM's normal optimization strategy.
- `bokfri context create` now uses Bokfri's platform-specific default data
  directory when `--data-dir` is omitted and derives its default name from the
  company and accounting-year start date; custom names remain available via `--name`.
- The bundled demo company is now generated through Bokfri's Java services instead
  of serialized SQL, with completed 2025/26 and ongoing 2026/27 accounting years.
- Windows MSI packages now install `bokfri` (`bokfri-dev` for development builds)
  on the system PATH, use stable channel-specific upgrade identities, and verify
  command discovery plus exact PATH cleanup during uninstall in CI.
- Global CLI options now work before or after nested commands. VAT, trial balance,
  income statement, and general-ledger reports default to the full selected
  accounting year; balance sheet and account balance default to its final day.

### Added
- Added application-level database format metadata and startup compatibility checks;
  databases from newer unsupported Bokfri formats are rejected before normal loading.
- Added versioned JSON manifests to new full backups while retaining verification
  and restore support for legacy v1.0.1 backups without manifests.
- Added checked-in v1.0.1 database and backup fixtures plus integration tests for
  direct opening and backup verification, restoration, and opening.
- Added GUI and `bokfri demo recreate [--commit]` actions for safely replacing
  recognized current or legacy demo companies while preserving all other companies.
- Added privacy-friendly GoatCounter visitor statistics to the website and help pages.
- Added the first headless CLI slice with version, path, diagnostics, named
  company/year contexts, and read-only company and accounting-year commands.
- Added text and JSON output plus isolated config and context overrides for
  scripts, CI, and agent-driven automation.
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
  and verifies contexts, JSON contracts, account/year lookup, voucher
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
- Expanded packaged CLI regression coverage for invalid contexts, out-of-year
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
