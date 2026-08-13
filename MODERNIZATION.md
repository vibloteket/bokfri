# Bokfri Modernization Backlog

This file tracks only modernization work that is still incomplete.
Completed work belongs in `CHANGELOG.md` and git history.

## Current Constraints

- Persistence still stores Java-serialized domain objects in HSQLDB 1.8.
- Data format 2 and the tested format 1 → 2 migration provide a foundation for
  future storage migrations, but do not replace serialization-based storage.
- The Swing UI still depends heavily on IntelliJ GUI Designer forms.
- Some obsolete libraries remain in active use.
- Static-analysis checks report results but do not yet block all regressions.

## Remaining Work

### 1. Replace Serialization-Based Persistence

Status: not started

Bokfri still stores serialized Java objects in HSQLDB and many production
classes therefore remain `Serializable`. Model changes require careful Java
serialization compatibility handling.

Remaining tasks:

- Choose the target persistence strategy:
  - normalized SQL schema,
  - explicit document serialization,
  - or a transitional representation on a maintained database engine.
- Design the next versioned migration using the existing data-format,
  pre-migration backup, verification, and compatibility-fixture infrastructure.
- Build migration tooling that reads existing format 2 databases and writes the
  new representation without changing accounting results.
- Upgrade or replace HSQLDB 1.8 as part of the storage transition.
- Remove Java serialization from domain and backup models once no persisted
  representation depends on it.
- Preserve backup verification and restore compatibility across the transition.

Done when:

- new databases use an explicit, maintainable persistence format;
- format 2 user databases migrate safely with a verified rollback artifact;
- domain model evolution no longer depends on Java serialization field layout.

### 2. Replace Obsolete Dependencies

Status: partially complete

Remaining active dependencies include:

- `javax.mail:mail` in mail/report delivery code;
- JExcelAPI (`jxl`) in Excel import/export;
- IntelliJ GUI Designer compiler/runtime support for roughly 100 `.form` files.

Remaining tasks:

- Replace `javax.mail` with Jakarta Mail.
- Replace JExcelAPI with Apache POI or another maintained spreadsheet library,
  preserving import/export compatibility through fixture-based tests.
- Decide whether to retain IntelliJ GUI Designer as a supported source format or
  incrementally replace generated forms with ordinary Swing construction.
- Remove redundant legacy IDEA build dependencies and repositories after the
  form strategy is settled.

Done when obsolete libraries and unnecessary build repositories are absent from
`pom.xml`, and affected workflows have regression coverage.

### 3. Finish Separating Application Services from Swing and `SSDB`

Status: in progress

The CLI now covers database setup, customers, products, suppliers, invoices,
credit invoices, payments, journals, VAT, vouchers, backups, SIE, financial
reports, and invoice PDF generation. Cross-platform black-box tests exercise a
complete isolated accounting workflow. Expanding the command list is therefore
no longer the main modernization task.

Remaining tasks:

- Move business operations that still call `SSDB`, report printers, or backup
  helpers directly behind small UI-independent services.
- Make Swing and CLI entry points use the same validation and transaction
  boundaries for each write workflow.
- Add focused headless regression tests when a GUI-only defect is found rather
  than duplicating the workflow in another interface.
- Add a compact database-health/status operation only if it provides diagnostics
  not already available through existing commands and backup verification.

Done when core write workflows are expressed through shared services and Swing
panels primarily collect input and present results.

### 4. Enforce Build and Quality Gates

Status: partially complete

Current gaps:

- Checkstyle has `failOnViolation=false` and GitHub CI treats its step as
  `continue-on-error`.
- SpotBugs has `failOnError=false` and is not a blocking CI check.
- JaCoCo reports are produced, but no coverage policy is enforced.

Remaining tasks:

- Reduce or explicitly baseline Checkstyle findings, then make violations fail
  local builds and CI.
- Reduce or explicitly baseline SpotBugs findings, then make high-confidence
  findings block CI.
- Decide and document whether coverage is informational or subject to a minimum
  threshold.
- Keep enforcement consistent across supported CI environments where practical.

Done when style and static-analysis regressions block merges and the coverage
policy is explicit.

### 5. Targeted Legacy API Cleanup

Status: ongoing, low priority

Many remaining `null` returns occur at Swing, table-model, printer, importer, or
framework boundaries where `null` is part of the contract. A blanket conversion
to `Optional` would make those APIs worse.

Remaining tasks:

- Clean up nullability while touching related production code.
- Use `Optional<T>` for domain/service lookup results where absence is meaningful.
- Document or retain intentional `null` contracts at framework boundaries.
- Avoid broad mechanical rewrites without an observable correctness benefit.

Done when avoidable domain/service ambiguity has been removed and remaining
framework nullability is intentional.

## Suggested Order

1. Make Checkstyle and SpotBugs enforceable with reviewed baselines.
2. Replace the smallest obsolete dependencies first (`javax.mail`, then `jxl`).
3. Continue extracting shared application services from GUI-only workflows.
4. Decide the persistence target and prototype a format 2 migration on copied
   compatibility fixtures.
5. Address the IntelliJ form dependency incrementally rather than combining it
   with the persistence migration.

## Out of Scope

- replacing Swing with another UI framework;
- switching build tools;
- introducing dependency injection throughout the application;
- adopting JPMS;
- rewriting `SSDB.java` without an incremental storage/service migration plan.
