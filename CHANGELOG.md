# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Bokfri starts its own version history at 1.0.0. The project is a fork of 
[Fribok](https://fribok.org/) and 
[JFS Accounting](https://sourceforge.net/projects/jfsaccounting/),
diverging from upstream Fribok after version 2.2-SNAPSHOT.

## [Unreleased]

### Fixed
- Restored loading of voucher templates created before the `java.time`
  migration, preventing a `Serialization failure` when opening the new-voucher
  dialog for companies with legacy templates.

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
