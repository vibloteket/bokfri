# Bokfri Modernization Backlog

This file tracks only modernization work that is still incomplete.

Completed work belongs in `CHANGELOG.md` and git history, not here.

## Current Snapshot

| Area | Current state |
|------|---------------|
| Java target | 21 |
| Tests | JUnit 5 + integration tests in place |
| Logging | SLF4J + Logback in place |
| Build tooling | Checkstyle, SpotBugs, JaCoCo, and CI are configured |
| Persistence | Still based on Java serialization stored in HSQLDB `OBJECT` columns |

## Remaining Work

### 1. Replace Serialization-Based Persistence

Status: not started

Current repo state:
- `46` production classes still implement `Serializable`
- storage is still built around serialized objects in HSQLDB
- HSQLDB is still `1.8.0.10`

Remaining tasks:
- Decide the target persistence strategy
  - normalized SQL schema
  - JSON/text document storage
  - another transitional approach on newer HSQLDB
- Design a migration path from existing user databases
- Build a migration tool that can read old serialized-object data and write the new format
- Incrementally remove `Serializable` from domain and backup models once the storage layer no longer depends on it
- Keep backup/restore working across the migration

Done when:
- new persistence format is implemented
- existing user data can be migrated safely
- domain model evolution no longer depends on Java serialization compatibility

### 2. Replace or Remove Obsolete Dependencies

Status: partially complete

Current repo state:
- direct Xerces usage has been removed in favor of JDK XML APIs
- `javax.mail` still present in `pom.xml` and referenced from mail/report code
- `jxl` still powers Excel import/export code
- IntelliJ GUI Designer runtime/plugin is still required
- `javax.help:javahelp` is still present and actively referenced
- there are `111` IntelliJ `.form` files under `src/main`

Remaining tasks:
- Replace `javax.mail` with `jakarta.mail`
- Replace `jxl` with Apache POI or another maintained Excel library
- Decide whether to keep or eliminate IntelliJ GUI Designer as a build dependency
- Replace or remove JavaHelp

Done when:
- obsolete libraries are removed from `pom.xml`
- code paths using them have been migrated and verified

### 3. Expand the Headless Developer/Test CLI

Status: in progress

Current repo state:
- Bokfri is primarily a Swing application
- a headless CLI entry point now exposes version, paths, diagnostics, named
  company/year contexts, account/company/year/customer/product/invoice
  inspection, and manual voucher validation, dry runs, and creation
- text and JSON output, explicit config files, and per-command context overrides
  are available for scripts, CI, and agents
- the assembled CLI fat JAR is exercised as an external process on Linux,
  Windows, and macOS CI runners through a complete isolated voucher round trip
- many important workflows are still only easy to exercise through the GUI
- report and print bugs remain difficult to reproduce in CI because preview/print flows assume UI entry points
- manual vouchers now use shared UI-independent validation and an application
  service from both Swing and the CLI; other workflows still call lower-level
  pieces (`SSDB`, report printers, backup helpers) directly

Goal:
- add a small CLI for automation, diagnostics, and agent/developer testing without creating a second user-facing product
- keep the Swing app as the primary application
- use the CLI to make database, backup, and report behavior reproducible in CI and local debugging

Suggested CLI shape:
- add a new entry point such as `org.fribok.bookkeeping.cli.BokfriCli`
- keep commands thin and call existing services/printers directly
- avoid creating Swing frames/dialogs from CLI commands
- support Maven/fat-jar execution first; packaged launchers can come later

Implemented commands:
- `version` — print app version/build metadata
- `paths` — print resolved app/user/config/data paths
- `doctor` — inspect CLI config, selected context, and data directory
- `context create/list/show/current/use/delete` — manage named data/company/year selections
- `company list` and `year list` — inspect available company/year IDs for scripting

Also implemented:
- `account-plan list`, `company create`, and `year create` — bootstrap an
  isolated company and accounting year for automation scenarios
- `account list` — list accounts in the selected accounting year
- `customer validate` and `customer create [--dry-run]` — validate and create
  customers from JSON through a service shared with Swing
- `product validate` and `product create [--dry-run]` — validate and create
  products from JSON through a service shared with Swing
- `voucher validate` — validate JSON without writing
- `voucher create --dry-run` — resolve and preview the next number without writing
- `voucher create` — validate and persist through the shared voucher service
- `voucher list` and `voucher show` — inspect persisted vouchers and posting rows

Next command candidates:
- `db status` — open a configured database and report basic health/counts
- `invoice validate` and `invoice create [--dry-run]` — validate and create
  unbooked customer invoices through a service shared with Swing
- `vat report` and `vat settle [--commit]` — calculate Swedish VAT boxes and
  preview or persist the VAT settlement voucher
- `outpayment list/show/validate/create` and journal — pay booked supplier
  invoices and commit the period journal
- `supplier-invoice list/show/validate/create` and journal — register and book
  supplier invoices through the existing period journal model
- `supplier list/show/validate/create` — inspect and create suppliers before
  supplier invoice workflows
- `inpayment list/show/validate/create` and `inpayment journal` — register
  customer payments and preview or commit the period journal
- `invoice journal --from DATE --to DATE [--commit]` — preview or commit the
  existing period-based invoice journal and its compressed voucher
- `invoice pdf NUMBER --output invoice.pdf` — generate an invoice PDF headlessly
  without changing invoice status, with artifact checks in the CLI smoke test
- `invoice sample-pdf --out target/invoice-sample.pdf` — create a deterministic sample invoice PDF for CI smoke tests
- `backup create --out backup.zip` — smoke-test backup creation without navigating the UI

Testing opportunities:
- add CI smoke tests that generate a sample invoice PDF and assert it exists, is non-empty, and contains expected text/metadata where practical
- use CLI commands to reproduce report/printing bugs on Linux, Windows, and macOS runners
- use CLI commands for agent-driven verification before opening PRs

Packaging opportunities:
- eventually ship two launchers from the same codebase:
  - `Bokfri` for the Swing application
  - `bokfri-cli` for command-line diagnostics/automation
- keep official user workflows in Swing unless a CLI command is explicitly promoted to supported user-facing behavior

Done when:
- at least one headless report/PDF smoke test runs in CI
- common diagnostic commands can inspect database state and invoices without opening Swing UI
- write workflows call shared application services used by both Swing and the CLI

### 4. Tighten Build and Quality Gates

Status: partially complete

Current repo state:
- Checkstyle is configured, but `failOnViolation` is disabled
- SpotBugs is configured, but `failOnError` is disabled
- JaCoCo reports are generated
- PR CI runs `mvn clean install` on Linux, Windows, and macOS
- Linux CI runs Checkstyle as `continue-on-error`
- CI does not currently enforce SpotBugs or a coverage threshold

Remaining tasks:
- Reduce the existing Checkstyle baseline until violations can fail the build
- Reduce the SpotBugs baseline until issues can fail the build
- Decide whether to enforce a minimum coverage threshold
- Add any missing CI checks needed to make tooling enforcement consistent

Done when:
- style and static analysis checks can block regressions in CI
- coverage policy is explicit and enforced if desired

### 5. Optional API Cleanup After Core Modernization

Status: partially complete

Current repo state:
- `245` `return null` sites remain in production code
- many of the remaining sites are GUI, table-model, print, importer, or framework-boundary methods

Remaining tasks:
- Review remaining `return null` sites and separate intentional framework contracts from avoidable legacy API design
- Introduce `Optional<T>` only where it improves correctness and API clarity
- Avoid forcing `Optional` into Swing/table-model patterns where `null` is part of the expected contract

Done when:
- remaining `null` returns are either removed or intentionally documented by category

## Suggested Order

1. Add the first small headless CLI commands (`version`, `paths`, and an invoice sample PDF smoke test) so future modernization work is easier to verify
2. Tackle library migrations with the smallest blast radius first (`javax.mail`, `jxl`, `javahelp`)
3. Decide the persistence migration strategy before changing storage-related models
4. Tighten CI quality gates after the dependency and persistence work stops moving the baseline

## Out of Scope for This File

These may be worthwhile, but they are broader architecture work rather than backlog items for the current modernization pass:

- breaking up `SSDB.java`
- replacing Swing with another UI framework
- introducing dependency injection across the app
- switching build tools
- adopting JPMS
