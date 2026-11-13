# Normalized persistence design

Status: design and implementation record for issue #75. Normalized storage is opt-in;
normal GUI startup and new-database creation still use legacy format 2. See the
[completion audit and cutover checklist](normalized-cutover-checklist.md) for
remaining runtime operations, GUI integration and activation gates.

## Purpose

Bokfri data format 2 uses HSQLDB 2.5, but 30 of its 31 application tables still store a Java-serialized domain object in an `OTHER` column. A future data format must use explicit SQL columns, keys and constraints while retaining HSQLDB.

The first implementation slice is the accounting core: companies, accounting years, account plans, accounts, opening balances, budgets, vouchers and voucher rows. Other domains follow in bounded migrations after the core model and verification machinery have proved safe.

A portable CSV archive is not a prerequisite. A consistent physical HSQLDB backup is the rollback artifact. A documented SQL dump can later provide database-level portability; issue #74 should be reconsidered after normalization.

## Design principles

1. Never rewrite the only copy of a user's database.
2. Build and validate a complete staging catalog before activation.
3. Keep the legacy reader isolated; normal runtime must not mix old and new representations.
4. Preserve business identity separately from generated database identity.
5. Represent relationships with foreign keys, not embedded object references.
6. Use exact decimal and temporal SQL types; do not derive persisted values through locale-sensitive text.
7. Record every schema migration in order with a checksum.
8. Compare semantic content, not serialized bytes or generated row IDs.
9. Migrate one bounded domain at a time, but do not activate a partially normalized catalog as the normal format.

## Scope and non-goals

This design covers active domain persistence and database migration. It does not:

- replace HSQLDB;
- add a portable application archive;
- change accounting behavior, GUI workflows or CLI response shapes;
- include GUI preferences, logs or the currently selected company/year;
- add voucher attachments (issue #72 owns their content store and metadata);
- remove legacy classes until all supported databases and backups have a documented migration path.

## Current persistence inventory

`src/main/resources/sql/create_tables.sql` declares 31 application tables. Thirty contain exactly one `OTHER` column. `tbl_license` is already scalar.

| Domain | Legacy tables | Embedded content that must be flattened |
|---|---|---|
| Company | `tbl_company` | company details, two addresses, defaults, numbering state, mail settings and references to shared terms |
| Accounting core | `tbl_accountplan`, `tbl_accountingyear`, `tbl_voucher` | accounts, opening balances, budgets, voucher rows and correction links |
| Sales | `tbl_invoice`, `tbl_creditinvoice`, `tbl_periodicinvoice`, `tbl_order`, `tbl_tender`, `tbl_inpayment` | headers, document rows, taxes, payments and cross-document references |
| Purchasing | `tbl_supplierinvoice`, `tbl_suppliercreditinvoice`, `tbl_purchaseorder`, `tbl_outpayment` | headers, rows, taxes, payments and references |
| Registers | `tbl_customer`, `tbl_supplier`, `tbl_product` | addresses, contacts, prices, defaults and accounting references |
| Stock | `tbl_indelivery`, `tbl_outdelivery`, `tbl_inventory` | delivery/inventory headers and rows |
| Accounting helpers | `tbl_vouchertemplate`, `tbl_project`, `tbl_resultunit`, `tbl_autodist` | template and distribution rows plus account references |
| Shared lookup data | `tbl_currency`, `tbl_unit`, `tbl_deliveryway`, `tbl_deliveryterm`, `tbl_paymentterm` | descriptive and calculation fields currently stored in objects |
| Reports | `tbl_ownreport` | report structure and rows |
| Already scalar | `tbl_license` | no `OTHER` column; retention policy still requires a separate decision |

The inventory is guarded by `LegacyPersistenceInventoryTest`. Adding or removing a legacy `OTHER` table must therefore be deliberate and update this document and the migration plan.

Configuration outside the database is separate:

- `database.config` stores the locally selected company/year and is not business data.
- `bookkeeping.config` stores GUI preferences through Java serialization and is outside the database migration. It should be replaced separately, but must not block normalized accounting storage.

## Proposed accounting-core schema

The executable staging DDL starts with `db/normalized/V1__create_accounting_core.sql`; `V2__create_shared_lookups.sql` adds currencies, units, payment terms, delivery terms and delivery ways, `V3__expand_company.sql` flattens company settings, addresses, standard texts, default accounts and numbering counters, `V4__create_accounting_dimensions.sql` adds company-scoped projects and result units, `V5__create_accounting_templates.sql` adds voucher templates and automatic distributions with ordered rows, `V6__create_customer_register.sql` adds customers and their invoice/delivery addresses, `V7__create_supplier_register.sql` adds suppliers and their addresses, `V8__create_product_register.sql` adds products, localized descriptions, account overrides and parcel components, `V9__create_customer_invoices.sql` adds customer invoice snapshots, ordered rows and embedded voucher snapshots, `V10__create_customer_credit_invoices.sql` adds the corresponding credit-invoice graph, `V11__create_periodic_invoices.sql` adds periodic-invoice templates, generated instances and added flags, `V12__create_payments.sql` adds inpayments and outpayments with rows, default accounts and main/difference voucher snapshots, `V13__create_supplier_invoices.sql` adds supplier invoices with rows, default accounts and main/correction voucher snapshots, `V14__create_supplier_credit_invoices.sql` adds the corresponding supplier credit-invoice graph, `V15__create_orders_and_tenders.sql` adds customer orders and tenders with addresses, default accounts and ordered rows, `V16__create_purchase_orders.sql` adds purchase orders with supplier/delivery addresses, default accounts and ordered rows, `V17__create_stock_documents.sql` adds in/out deliveries and inventory counts with ordered product-quantity rows, and `V18__create_own_reports.sql` adds own reports with headings, account-row snapshots and monthly budgets. Generated IDs are local implementation keys; dates and business numbers remain explicit columns. Temporary `legacy_id` columns preserve the source row mapping during conversion and are not public business identifiers. The excerpt below documents the logical shape; the versioned resources are authoritative for exact key columns and constraint ordering.

```sql
CREATE TABLE company (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    legacy_id INTEGER NOT NULL UNIQUE,
    name VARCHAR(255),
    corporate_id VARCHAR(64),
    vat_number VARCHAR(64),
    currency_code VARCHAR(16),
    -- remaining scalar company fields are mapped explicitly
    CONSTRAINT uq_company_name UNIQUE (name)
);

CREATE TABLE accounting_year (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    legacy_id INTEGER NOT NULL UNIQUE,
    company_id BIGINT NOT NULL REFERENCES company(id),
    starts_on DATE,
    ends_on DATE,
    CONSTRAINT ck_accounting_year_dates CHECK (
        starts_on IS NULL OR ends_on IS NULL OR starts_on <= ends_on),
    CONSTRAINT uq_accounting_year_range UNIQUE (company_id, starts_on, ends_on)
);

CREATE TABLE account_plan (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    accounting_year_id BIGINT NOT NULL UNIQUE REFERENCES accounting_year(id),
    name VARCHAR(255),
    base_name VARCHAR(255),
    assessment_year VARCHAR(32),
    plan_type VARCHAR(32) NOT NULL
);

CREATE TABLE account (
    accounting_year_id BIGINT NOT NULL REFERENCES accounting_year(id),
    number INTEGER NOT NULL,
    description VARCHAR(1024),
    sru_code VARCHAR(64),
    vat_code VARCHAR(64),
    report_code VARCHAR(64),
    active BOOLEAN NOT NULL,
    project_required BOOLEAN NOT NULL,
    result_unit_required BOOLEAN NOT NULL,
    PRIMARY KEY (accounting_year_id, number)
);

CREATE TABLE opening_balance (
    accounting_year_id BIGINT NOT NULL,
    account_number INTEGER NOT NULL,
    amount DECIMAL(100, 30) NOT NULL,
    PRIMARY KEY (accounting_year_id, account_number),
    FOREIGN KEY (accounting_year_id, account_number)
        REFERENCES account(accounting_year_id, number)
);

CREATE TABLE budget_entry (
    accounting_year_id BIGINT NOT NULL,
    account_number INTEGER NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    amount DECIMAL(100, 30),
    PRIMARY KEY (accounting_year_id, account_number, period_start),
    FOREIGN KEY (accounting_year_id, account_number)
        REFERENCES account(accounting_year_id, number),
    CONSTRAINT ck_budget_period CHECK (period_start <= period_end)
);

CREATE TABLE voucher (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    accounting_year_id BIGINT NOT NULL REFERENCES accounting_year(id),
    number INTEGER NOT NULL,
    voucher_date DATE,
    description VARCHAR(4096),
    corrects_voucher_id BIGINT REFERENCES voucher(id),
    corrected_by_voucher_id BIGINT REFERENCES voucher(id),
    CONSTRAINT uq_voucher_number UNIQUE (accounting_year_id, number)
);

CREATE TABLE voucher_row (
    voucher_id BIGINT NOT NULL REFERENCES voucher(id),
    row_number INTEGER NOT NULL,
    account_number INTEGER,
    project_number VARCHAR(255),
    result_unit_number VARCHAR(255),
    debit DECIMAL(100, 30),
    credit DECIMAL(100, 30),
    edited_at TIMESTAMP WITH TIME ZONE,
    edited_signature VARCHAR(255),
    crossed BOOLEAN NOT NULL,
    added BOOLEAN NOT NULL,
    PRIMARY KEY (voucher_id, row_number),
    CONSTRAINT ck_voucher_row_side CHECK (debit IS NULL OR credit IS NULL)
);
```

### Open points to resolve with real fixtures

- Company name is currently treated as unique by services, but the staging schema deliberately permits null and duplicate names until historical fixtures prove a safe constraint.
- Accounting-year and voucher dates can be null in the object model. The staging schema preserves null and only rejects reversed year ranges when both endpoints exist.
- Empty voucher rows are currently valid UI state and may have been persisted. Migration must either preserve them with nullable account/value columns or prove they cannot exist in stored vouchers.
- `BigDecimal` scale has not been globally constrained. Staging uses `DECIMAL(100,30)` and round-trip tests values beyond ordinary money precision; fixture inventory must still prove whether this covers all supported data before activation.
- Voucher correction links are object references. Their identity and possible cycles/inconsistencies must be inventoried before adding both foreign keys.
- Account identity is scoped to an accounting year even though `SSAccount.equals()` compares only account number across years. The SQL model must not reproduce that accidental global identity.
- Project and result-unit foreign keys are deferred until those tables are normalized in the same activation candidate.
- `java.rmi.server.UID` appears in the obsolete `SSAccountingYear`; active `SSNewAccountingYear` uses the database integer ID. Fixtures must confirm which class variants remain stored.

## Date and time semantics

The schema distinguishes civil business dates from instants. The distinction is part of the data contract, not a JDBC formatting detail.

- Business dates such as accounting-year boundaries, voucher/invoice/payment dates, due dates and budget periods use Java `LocalDate` and SQL `DATE`. They have no time or time zone and must remain the same calendar date on every computer.
- New event times such as row edits, schema installation and future audit events use Java `Instant` and SQL `TIMESTAMP WITH TIME ZONE`. Writers persist an unambiguous instant, canonically represented in UTC; user-facing rendering uses `Europe/Stockholm`.
- Persistent event code obtains time from an injectable `Clock`, not directly from the host default time zone. Timestamp precision must be measured and round-trip tested through the supported HSQLDB/JDBC version.

Legacy serialized event fields use Java `LocalDateTime` and therefore contain no zone. Bokfri is a Swedish accounting application, so the compatibility rule is to interpret such values in the IANA zone `Europe/Stockholm`, never as a fixed `UTC+01:00` offset. This preserves Swedish daylight-saving rules. The resulting instant is stored in the normalized timestamp column; the migration must not silently use `ZoneId.systemDefault()`.

Daylight-saving transitions require explicit deterministic handling:

1. With one valid Stockholm offset, use it.
2. In a spring gap, move the local value forward by the transition gap duration.
3. In an autumn overlap, choose the earlier offset (the first occurrence, normally summer time).
4. Count and report every gap adjustment and overlap resolution in the migration result.

The source and destination semantic fingerprints apply the same conversion rule. Canonical fingerprint values are ISO `YYYY-MM-DD` for business dates and UTC instants for event times. Migration tests must cover ordinary winter and summer values, a Stockholm spring gap, an autumn overlap, null values and the timestamp precision retained by HSQLDB.

Technical filenames may contain a formatted timestamp for uniqueness, but filenames are not authoritative event times. New persisted metadata records the actual instant independently.

## Schema history

Data format 2 identifies the current application format, but `BOKFRI_METADATA` is not yet a sequential, checksummed migration history. Add a dedicated table before the first normalized schema migration:

```sql
CREATE TABLE bokfri_schema_history (
    version INTEGER PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    script_name VARCHAR(255) NOT NULL UNIQUE,
    script_sha256 CHAR(64) NOT NULL,
    installed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    application_version VARCHAR(64) NOT NULL
);
```

Rules:

- migration resources are immutable and named `V<version>__<description>.sql`;
- every version from the source to target must be present and applied once in order;
- the SHA-256 recorded at installation must match the packaged resource on every later startup;
- an unknown, missing or changed migration aborts startup before domain loading;
- a migration updates the application data-format version only after all staging validation succeeds;
- migrations must be repeatable from every explicitly supported source fixture, not merely idempotent on a partially changed live database.

## Staged migration and activation

The engine-upgrade flow in `HsqlEngineMigrationService` is the pattern to extend, not code to bypass.

1. Obtain exclusive application ownership and cleanly checkpoint/shut down the source catalog.
2. Create and fully read a physical rollback ZIP containing every `JFSDB.*` file, including `.lobs` when present.
3. Retain an unpacked source copy under `backups/`; never mutate it.
4. Copy the source catalog to a uniquely named staging directory on the same filesystem as the active database.
5. Open staging with the legacy compatibility reader.
6. Create normalized tables and copy one domain in dependency order.
7. Validate structure, row relationships and semantic fingerprints.
8. Shut down staging cleanly and reopen it once to verify durability.
9. Move the active directory aside, then move staging into place. Prefer an atomic rename; if unsupported, retain enough state for deterministic recovery.
10. Only after successful activation may later cleanup remove legacy tables. Legacy rollback artifacts follow an explicit retention policy and are never deleted in the migration transaction.

On any failure before activation, delete staging and leave the source untouched. On activation failure, restore the moved source directory. Startup must recognize abandoned staging/activation directories and report recovery instructions instead of guessing.

## Semantic verification

Byte equality is impossible after normalization. Verification therefore compares a deterministic snapshot extracted independently from source and destination.

The accounting-core fingerprint contains:

- company count and stable company attributes;
- accounting-year count, date ranges and ownership;
- account count and canonical account fields per year;
- opening balances by year/account;
- budget values by year/account/period;
- voucher count and ordered fields per year;
- voucher rows in stored order, including null versus zero, edit metadata and project/result-unit numbers;
- correction relationships expressed through year and voucher business number;
- debit and credit totals per voucher, year and company;
- SHA-256 of canonical length-prefixed values for each domain and for the aggregate.

Canonical values use UTF-8, ISO `YYYY-MM-DD` business dates, UTC event instants, exact `BigDecimal.toPlainString()` values with documented scale handling, explicit null markers and deterministic key ordering. Legacy `LocalDateTime` values are first resolved with the documented `Europe/Stockholm` transition rules. Fingerprints ignore generated destination IDs but do not ignore row order, null/empty differences, decimal values or relationship direction.

A mismatch aborts activation and reports the first differing domain plus counts/totals; a bare hash mismatch is not actionable enough.

## Delivery slices

1. **Design and inventory** (this change): document the model, freeze the legacy table inventory and identify unresolved fixture questions.
2. **Migration foundation**: checksummed schema history, migration runner, recovery-state detection and source/destination fingerprint API; no active schema replacement.
3. **Accounting-core staging migration**: normalized core tables and conversion on copied compatibility fixtures; source remains active.
4. **Accounting-core activation**: repository adapters, full validation, packaged smoke tests and safe directory activation.
5. **Remaining domains**: company details/lookups, registers, sales/purchasing, stock, helpers and reports in dependency-ordered slices.
6. **Legacy retirement**: remove runtime `OTHER` access and serialization only after all supported backup/database paths are covered.

Each implementation slice requires `mvn verify`, representative old databases, empty/minimal/multi-company/multi-year fixtures, interruption tests and packaged Linux/Windows/macOS startup checks.

## Global account-plan catalogue staging and runtime

Schema migration `V19__create_account_plan_catalog.sql` preserves the reusable
`tbl_accountplan` templates in `account_plan_catalog` and ordered
`account_plan_catalog_account` rows. These are global templates, **not** the
per-year `account_plan`/`account` copies. Unused and empty templates, duplicate
names with distinct IDs, base/assessment/type metadata, account order, all codes
and active/project/result-unit flags are fingerprinted separately. Inconsistent
legacy object keys, invalid accounts and duplicate account numbers reject staging
rather than silently dropping data. Catalogue fingerprints are checked before
commit, after staging shutdown/reopen and again after activation. Normalization
JSON reports the verified template/account counts.

On V19 catalogues, `account-plan list`, `account-plan export --id ... --output ...`
and `account-plan import --file ...` (preview or `--apply`) use explicit typed
storage without requiring a company/year. Reads return detached templates and
preserve stored account order, flags and nullable metadata. Import preview writes
nothing; applying a new template commits its header/accounts together and assigns
a fresh ID. Duplicate names are reported in preview and rejected on apply. Failed
writes roll back all new rows and allow retry; exports retain overwrite protection.
The existing XLSX format is unchanged: this does not add base-name or account-flag
columns, and workbook imports retain the legacy spreadsheet defaults for those fields.
Unknown stored plan types fail rather than being silently replaced with BAS95.

Catalogue commands require both tables and the recorded V19 migration. Earlier
format-3 snapshots fail with `NORMALIZED_ACCOUNT_PLAN_CATALOG_REQUIRED` before
writing output or changing data/schema; they are not silently upgraded or seeded
with replacement templates. Their original global catalogue was not included in
those snapshots. Recovery needs the retained legacy source/backup and must retain
any newer accounting changes in the active normalized database. The explicit V18
recovery path below supplies that catalogue; normalized GUI activation remains follow-up work.
Normal startup and an already-normalized `database normalize` no-op do not rewrite
such databases.

## Explicit catalogue recovery for earlier normalized V18 databases

Close every Bokfri process and obtain exclusive ownership of the data directory.
Use the **original, operator-trusted legacy rollback ZIP** retained by the original
normalization, not an older application backup chosen merely because its name looks
similar. Verify its provenance and independently establish its SHA-256 first:

```sh
bokfri --data-dir /path/to/data database recover-account-plans \
  --trusted-legacy-backup /path/to/bokfri-before-normalized-TIMESTAMP.zip \
  --sha256 EXPECTED_64_HEX_DIGIT_SHA256 --commit
```

This is a recovery operation, not normal startup or a generic schema upgrade.
Without `--commit` it does nothing. It accepts only format 3 with exactly V18
history, validates all installed migration checksums in staging, and never
replaces an existing/partial V19 catalogue. Missing or future schemas are rejected.
The legacy ZIP must contain a complete, cleanly shut down HSQLDB catalogue with
`JFSDB.*` files at its root (or under `db/`), `modified=no`, and no lock/log file.
Arbitrary application/JAR archives and archives needing crash recovery are not
accepted. A clean old-engine catalogue is upgraded only inside the disposable copy.

**Trust boundary:** the operation deserializes Java objects from the extracted
legacy copy. A matching SHA-256 proves byte integrity, **not authenticity or that
the backup belongs to this database**. The operator must establish both provenance
and the correct original source. Never use an untrusted ZIP. Only global account-plan
templates are read from it; no company, year, voucher, balance or document data is
restored from legacy storage. The supplied ZIP remains unchanged.

The service copies the **current** normalized catalogue, applies V19 only there,
and converts/verifies the global templates. A deterministic SQL fingerprint covers
every pre-existing PUBLIC table, including all V1–V18 history rows, nullable/binary
values and duplicate rows. Unsupported object/LOB SQL columns fail closed. Existing
accounting data can therefore have evolved since the original normalization.
The staged catalogue is shut down and reopened for row/catalogue verification.
Active physical file hashes are checked again before activation to reject changes
during staging; this is an additional guard, not a replacement for exclusive access.

Activation uses the existing verified physical rollback ZIP plus retained source
folder and directory-swap mechanism. Here the retained source is the **pre-recovery
normalized** database, not a legacy replacement. JSON reports the source digest,
verified plan/account counts, rollback ZIP and retained source path. Reopened active
data and templates are checked again. Saved company/year configuration is untouched.
After success the existing catalogue commands and `year create` work normally.

Failures before activation leave the active files untouched and retain staging for
inspection. Pending staging blocks retry. Inspect it and move it outside the data
directory before deliberately retrying; do not discard recovery evidence blindly.
Successful prior migrations' retained sources are permitted, since they are normal
rollback artifacts. If activation itself is interrupted, use the existing manual
migration recovery procedure and the preserved normalized source/ZIP; do not run
normalization again against an old legacy backup. This command does not enable the GUI
or remove the remaining legacy persistence paths in issue #75.

## Company creation runtime support

`company create --file` supports normalized format 3 without opening legacy object
storage. It retains the existing JSON schema, validation and duplicate-name rules.
All supported creation fields, default accounts, currency, payment terms and unit
are saved in one transaction. Registered lookup values take precedence over the
built-in fallback definitions; missing fallback definitions are inserted without
replacing existing descriptions or currency rates. Company reads restore those
references, VAT period and contact person after reopening. Failed writes roll back
both the company and newly inserted lookups; creation does not change the saved
company/year selection. Normalized GUI activation remains separate follow-up work.

## Explicit demo recreation runtime support

`demo recreate` previews recognized demo removals and the replacement without
writing; `demo recreate --commit` also supports normalized format 3 with V19.
Recognition requires **both** the exact demo name and corporate ID (current or
legacy identity). A matching corporate ID alone never authorizes removal. A
non-demo company already using the replacement name causes an error instead of
being removed or overwritten.

Normalized recreation validates the global `BAS 2026 - Aktiebolag` template and
required posting accounts before removal. It creates independent accounting years
for July 2025–June 2026 and July 2026–June 2027, six historical and four current-year
vouchers, carried opening balances, two customers/suppliers/products, two booked
invoices and one booked customer payment. Shared seed factories preserve the legacy
demo content and explicitly provide company defaults (including product units,
payment account mappings and the empty difference-voucher snapshot). Invoice/payment
voucher calculations receive the account plan and rounding settings explicitly;
normalized reads/writes never open legacy object storage.

All recognized demo graphs are removed and the replacement is inserted in **one
SQL transaction**, without disabling constraints/triggers or making a temporary
fallback company. Existing global templates and shared lookup rows are not deleted.
Company/year IDs are allocated across the current database before removal. The
company-deletion writer covers every company-owned normalized domain in dependency
order, including document rows/snapshots, periodic invoice instances, payments,
registers, reports, templates, dimensions and accounting years. Additional restrictive
foreign keys fail the operation rather than being bypassed or silently detached.

A failure rolls back both the old demo removal and every replacement row; runtime
company/year selection remains valid and retry is possible in the same runtime.
After success runtime selection points to the replacement/current demo year. Saved
configuration changes only under the existing CLI rule: when the saved selection
was a removed demo and no explicit `--company-id` override was supplied, select the
replacement/current year. Ordinary saved selections and explicit overrides do not
write configuration. Empty and demo-only normalized databases are supported, but
nothing is automatically seeded at startup.

V18 preview remains read-only; commit fails through the existing V19 catalogue
guard without schema or data changes. This does not enable normalized GUI startup
or retire the remaining legacy persistence paths in issue #75.

## Accounting-year creation runtime support

`year create --file` supports format 3 with the V19 global catalogue. The existing
input schema selects templates by ID or name and must match exactly one template;
missing/ambiguous matches keep `ACCOUNT_PLAN_NOT_FOUND`. Shared pure preparation
preserves legacy date validation and inclusive overlap rules within the selected
company. Single-day years are permitted, adjacent non-overlapping periods are
permitted, and another company's dates do not block creation.

A year receives an independent deep copy of the template's accounts/codes/flags.
The copied plan's base name becomes the template's name, its template ID is not
retained as a year-plan ID, and the global template is unchanged. Normalized year
IDs are allocated across all companies. The year/header/accounts commit together;
failed inserts roll everything back and allow retry. Opening balances, vouchers
and budget amounts start empty, matching legacy creation. Existing normalized
account reads retain their normal numeric ordering. Creation neither selects the
new year nor changes saved company/year configuration; `year use` remains explicit.
Older normalized catalogues fail through the same V19 guard without manufacturing
templates, upgrading schema or changing accounting data.

## Explicit voucher calculation and reconstruction boundary

`SSVoucher(int, LocalDate)` initializes only its supplied number/date and empty
rows, without looking up a selected year, next number, legacy connection or current
clock. Nullable dates remain nullable for historical snapshots. The existing
zero/one-argument constructors retain their legacy default behavior.

`SSVoucherMath.compress(voucher, accounts, projects, resultUnits)` resolves rows
through explicit lookup overloads, not singleton-backed getters. It retains legacy
row ordering, first-row edit/crossed/added metadata, correction links, zero/negative
value handling and grouping by resolved references. As in legacy compression,
unresolved references resolve to null and may group together; this is not a new
raw-identifier regrouping policy. The input rows/amounts are unchanged (existing
transient reference caches may be populated during lookup). The old one-argument
compression API remains available for legacy callers.

Customer/supplier invoice and credit-invoice generation now uses explicit headers,
compression contexts and known-account correction totals. Incoming/outgoing payments
also accept `(accountPlan, projects, resultUnits)`; the account-plan-only overload
uses empty dimension lists and never fetches missing dimensions from SSDB. Callers
with raw dimension references must supply the full context. No-argument booking
APIs still obtain the legacy context before delegating. Difference/correction totals
use explicit known-account debit/credit totals and exclude crossed rows without
calling singleton-backed row validity.

Normalized runtime payment generation and all six CLI journal planners supply the
full account/dimension context. Their combined headers, voucher JSON input, core
voucher reads, invoice/payment/supplier snapshots and shared demo voucher factories
use explicit stored/supplied dates and numbers. Payment difference rows with the
same account but distinct known dimensions therefore stay separate even when no
legacy company is selected. Snapshot headers no longer briefly initialize from an
unrelated selected legacy year before being overwritten.

Tests poison legacy JDBC access and selected-year account lookup to verify explicit
header construction, compression, all six booking families and core voucher reads;
legacy/explicit results, stored null dates and correction links are compared.
This boundary does **not** remove all legacy hooks from parent domain constructors
or no-argument entity getters, enable normalized GUI startup, or change schemas,
serialization identifiers, stored accounting values or migration/startup policy.

## Financial PDF runtime support

The CLI `balance-sheet --output`, `income-statement --output`,
`general-ledger --account ... --output`, `vat report --output`,
`vat control --output`, `customer-revenue --from ... --to ... --output`,
`supplier-revenue --from ... --to ... --output` and
`product-revenue --from ... --to ... --output` and
`sales-report --from ... --to ... --output` and
`sale-values --from ... --to ... --output`,
`accounts-receivable --date ... --output`, `customer-claims --date ... --output`,
`accounts-payable --date ... --output` and `supplier-debts --date ... --output` paths
support normalized data format 3. They hydrate the selected year's accounts,
opening balances and vouchers, then pass the selected company and year explicitly
to the existing report printers. The shared printer header obtains the company
name and maximum voucher number from those arguments, not the SSDB singleton.
Existing legacy constructors retain their previous behavior.

`account-plan pdf --output` also supports format 3. It loads the selected year's
account plan and vouchers for the explicit company/maximum-voucher header, then
uses the unchanged account-plan template and active-account filtering. Account
numbers, descriptions, VAT/SRU/report codes, empty plans and overwrite protection
are preserved. This selected-year PDF path is separate from the global template
catalogue above and does not require V19.

`voucher show NUMBER` supports format 3 in text/JSON, including correction links
and row edit metadata. Its optional `--output` PDF uses explicit company/year
headers for both report levels and explicit account/project/result-unit lookups.
Missing references render blank as before; raw stored dimension numbers remain
in JSON. Explicit debit/credit totals include only non-crossed rows with an
account in the selected year's plan and a value on that side, without invoking
singleton-backed row validity checks. Existing templates and GUI constructors
remain unchanged.

The same Jasper templates and accounting calculators are used for both formats.
Regression tests compare extracted legacy and normalized PDF text for a bounded
period, verify overwrite protection, check empty-year rendering and unsorted
voucher-number headers, and ensure normalized exports leave legacy selection empty.
Income statements additionally load all years and dimensions of the selected
company and the selected year's budget through the normalized repositories. The
explicit calculator context preserves the legacy same-calendar-period-minus-one-year
comparison, including leap days and non-calendar fiscal years; it does not assume
that a single adjacent accounting year contains the comparison period. Account
numbers resolve against the selected account plan and validity is checked without
the singleton-backed voucher-row accessors. Existing GUI/legacy constructors still
use the original path. Tests compare legacy and normalized default, budget and
previous-year PDFs with a second company present, and exercise crossed/invalid rows
and empty/missing comparison periods. The CLI retains its existing report options;
this does not add comparison flags.

VAT PDFs reuse the existing 2015 templates and VAT box/group formulas. Both outer
printers and the VAT report's nested row printer receive explicit company/year
headers. Explicit voucher totals resolve account numbers against the selected
account plan and skip invalid/crossed rows without singleton-backed row helpers.
Legacy truncation/rounding remains unchanged. VAT control `--start-voucher` retains
its existing one-based index into date-filtered vouchers, **not a voucher number**;
an out-of-range index falls back to the complete period, as in the GUI printer.
Explicit VAT control printers render PDFs only: their legacy `getVoucher` method
fails fast; normalized settlements continue through `VatService`.

Tests compare legacy and normalized VAT PDF text before settlement, exercise valid
and out-of-range start indices, verify overwrite protection and render after
settlement. Explicit rows whose singleton-backed helpers throw cover populated
report/subreport rendering and aggregation. Empty-year/header coverage and the
cross-platform assembled/packaged CLI smoke flow include both VAT PDFs.

Customer-, supplier- and product-revenue reports receive explicit register, invoice and
credit-invoice lists from the selected company's runtime repositories. Both the
outer printers and monthly subreports use explicit company/year headers. Invoice
dates, not the selected accounting-year boundary, determine inclusion, matching
the legacy behavior for cross-year periods. Existing net/discount calculations,
nullable currency-rate fallback, credit subtraction, full-month bucket keys and
Jasper amount formatting are unchanged. The CLI sorts a copy of register entries
by business number (nulls last) on both backends to avoid storage-dependent row
ordering; direct GUI/legacy printer constructors retain their original behavior.

Revenue tests check exact monthly data-source totals, credits-only and inactive
registers, empty reports, source immutability, boundary dates and currency rates.
CLI PDF text matches legacy output for populated/partial/cross-year periods, with
a second company using the same register numbers; overwrite and singleton isolation
checks are included. The assembled/packaged smoke flow exports all three reports.
Product revenue sums discounted sale rows by their stored product number, converts
using each document's currency rate and subtracts matching credit rows; it does not
resolve live product objects or use current register prices. Explicit tests reject
implicit product lookups and cover monthly separation, leap day, year rollover,
missing row amounts/product numbers and caller-supplied register order.

The existing first-month visibility behavior is deliberately preserved in this
persistence slice: a period beginning mid-month can hide that month and its total.
Issue #158 tracks correcting the shared month/report behavior for both backends.

Sales reports receive explicit products, invoices, credits and supplier invoices.
They preserve inclusive sale-date filtering, latest purchase cost through the end
date (including freight and currency conversion), stock-price fallback and historical
parcel-cost behavior. Credit row totals are historically subtracted without currency
conversion; this slice deliberately preserves that behavior. All four sort modes are
retained, including the historically named `AverageSellingPrice` mode that actually
sorts unit contribution. Input product and purchase lists are copied before sorting.
Tests compare legacy/normalized PDF text across periods and company boundaries;
the assembled/packaged smoke flow also exports the sales report.
`sale-values` uses a narrow read-only `NormalizedSaleValuesStore` projection of
tender, order, invoice and credit-invoice dates, effective currency rates and discounted
row amounts scoped to the selected company. It does not return partially hydrated
editable documents. Row sums reuse `SSSaleRow.getSum()` without product lookups;
monthly accumulation retains legacy double arithmetic and subtracts credit totals
only after accumulating invoices and credits separately. The printer receives an
immutable `SaleValuesData` projection and explicit company/year header context.
Both backends use the same templates, including the summary band.

This report historically includes the **whole calendar month**, even if the requested
period starts or ends mid-month. Its existing month splitter excludes an end month
when the end date is that month's first day; both behaviors are deliberately retained.
There is no invoice-style null-rate fallback added here: stored/effective rates are
used as before. Normalized migration already snapshots tender/order effective rates.
Tests cover full/partial/cross-year PDF parity, selected-company isolation, discounted
amounts, rates, credits, empty data, null dates/missing amounts, first-day end boundaries,
immutable inputs, overwrite protection and packaged CLI smoke.

Customer outstanding-balance PDFs (`accounts-receivable` and `customer-claims`)
receive explicit company/year, invoices, linked credits and inpayments; receivables
also receive the customer register. `SSCustomerBalanceMath` builds company-scoped
cutoff sums without global customer/payment caches. Cash invoices, future invoices
and undated documents are excluded; dated credits and payments on the cutoff are
included. Credits/payments are subtracted in invoice currency before local conversion,
matching legacy behavior (no extra conversion using the credit's currency rate).
Tax and total rounding use the selected company's explicit rounding flag. Claims
round the remaining balance to two decimals before conversion; receivables retain
the unrounded remainder. Zero/negative balances and inactive customers remain visible.
Both outer and nested receivable printers receive explicit headers. Input register
lists are copied before sorting; legacy GUI constructors are retained. Tests cover
populated/empty reports, cash/date rules, tax/rounding, credits/payments, four cutoff
PDF comparisons across storage formats, two-company isolation, overwrite protection
and assembled/packaged CLI smoke.

Supplier outstanding-balance PDFs (`accounts-payable` and `supplier-debts`) likewise
receive explicit company/year, invoices, linked supplier credits and outpayments;
payables also receive a copied supplier register. `SSSupplierBalanceMath` aggregates
payments and linked credits through the inclusive cutoff without singleton caches.
Undated/future invoices and adjustments are excluded. Supplier totals use their
**stored tax and rounding amounts**, not the company's customer-sale rounding flag.
Credits/payments are subtracted in invoice currency before local conversion, without
extra conversion using adjustment currency rates. Debts round the remaining balance
to two decimals before conversion; payables retain unrounded remainders. Zero/negative
balances and inactive suppliers remain visible. Payable invoice subreports also receive
explicit headers. Legacy constructors/templates are unchanged. Tests cover stored
amounts, cutoff boundaries, multiple adjustments, overpayments, null rates, inactive
suppliers, nested rows, empty/populated PDFs, four-cutoff legacy/normalized text parity,
two-company isolation, overwrite protection and assembled/packaged CLI smoke.

Company-wide party register PDFs (`customer list --output` and `supplier list --output`)
use explicit company headers and copies of the supplied register lists in normalized
mode. These commands do not select an accounting year; the header's last-voucher
number is therefore zero, matching the company-only legacy CLI path. Companies with
no accounting years are supported without manufacturing a year object or querying
legacy vouchers. Existing fields, business-number sorting and templates are unchanged;
GUI/legacy constructors retain their previous behavior. Tests compare populated and
empty legacy/normalized PDF text with duplicate numbers in another company, verify
field mappings, input-list preservation, overwrite protection and cleared singleton
selection, and exercise both exports in assembled/packaged CLI smoke.

Product-register PDF (`product list --output`) and XLSX (`product export`) use a
read-only `NormalizedStockStore` projection for all-time stock. It reads only the
selected company's row quantities and flags, not partial editable documents. Quantity
is minus stock-influencing customer invoices, plus customer credits, plus supplier
invoices, minus supplier credits, plus inventory changes and indeliveries, minus
outdeliveries. Reservations come from customer orders without an invoice reference;
ordered counts come from stock-influencing purchase orders without an invoice reference.
As in `SSStock.update()`, no date/year filter is applied and inventory **change** is used,
not the stock snapshot value. Non-stock products retain zero counts. Sale/purchase
rows expand parcels one level; inventory/delivery changes remain direct product-number
adjustments. Customer invoice/credit rows require a registered stock product or parcel.
Decimal sale quantities retain exact integer conversion (fractional values fail, never
truncate). Existing integer arithmetic and nullable-quantity skipping are preserved.

`SSStock` accepts explicit count maps as a detached snapshot. Product PDF headers,
parcel product/unit lookups and description fallback use the explicit register without
mutating row caches or input list order. The company-wide PDF does not require a year;
XLSX retains its existing required year selection, but stock still spans all years.
Legacy GUI constructors and templates remain unchanged. Tests compare all nine stock
sources, reservation/ordered/available counts, parcel-expanded PDF text and all XLSX
cell values across migration, with duplicate product numbers in another company,
linked/non-influencing exclusions, fractional failure parity, empty/populated rendering,
overwrite protection and singleton isolation. Assembled/packaged smoke includes both exports.

Payment-list PDFs (`inpayment pdf-list` and `outpayment pdf-list`) receive the selected
company, hydrated year and company-wide payment snapshots explicitly. The year supplies
voucher-header context only; these lists retain their existing all-date behavior.
Outer and nested row printers both use explicit headers. Invoice numbers, invoice
currency/rate snapshots, payment values and payment rates come directly from stored
rows without resolving live invoices. Existing total helpers and nullable local-value
behavior are unchanged: a missing payment rate falls back to the row value in the
outer total, while the nested local-value cell remains empty. Input payment lists are
copied before number sorting; templates and legacy GUI constructors remain unchanged.
Tests cover empty/populated PDFs, sorting/immutability, snapshot fields, negative and
missing amounts/rates, unsorted voucher headers, exact legacy/normalized PDF text,
company isolation, overwrite protection and assembled/packaged CLI smoke.

Payment journal PDFs (`inpayment journal --output` and `outpayment journal --output`)
receive explicit company/hydrated-year context, planned payments and company project/
result-unit lookups. `SSJournalRowContext` resolves voucher-row account numbers against
the selected account plan and dimension numbers against supplied company registers,
without invoking singleton-backed or cache-mutating row getters. Unknown references
remain blank, as with unresolved legacy lookups. Both outer and nested voucher printers
use explicit headers; payment input lists are copied before sorting. Debit/credit rows,
local payment totals, journal dates/numbers, templates and legacy GUI constructors are
unchanged. Existing preview/commit ordering is retained: PDF export happens before any
booking writes; failed export/overwrite protection must not mark payments entered.
Tests compare exact legacy/normalized PDF text and company isolation, check read-only
preview and failed-export behavior, then commit and verify repeat booking is rejected.
Explicit row tests make implicit account/project/result-unit helpers throw and cover
missing references, dimensions, totals, caller ordering and empty/populated rendering.
Assembled/packaged smoke creates normalized payments and exports both preview and
commit journals. Supplier journals are covered below.

Customer invoice journals (`invoice journal --output`) and credit-invoice journals
(`credit-invoice journal --output`) also support normalized storage. The credit command
now exposes optional `--output`/`--overwrite` on both backends; its existing no-output
preview/commit metadata, error behavior and booking flow remain unchanged. Both report
printers receive company/hydrated year, planned invoice snapshots and explicit company
dimensions, and reuse `SSJournalRowContext` for account/project/result-unit resolution.
Outer and nested headers avoid legacy selection; input lists are copied before sorting.
Sale totals use the explicit company's rounding flag, while existing stored tax-rate,
discount, tax rounding and invoice currency conversion calculations are retained.

PDF generation precedes booking, so failed export must leave documents unentered.
Tests compare exact legacy/normalized invoice and credit-journal PDF text, company
isolation, preview/overwrite behavior and successful commit exports. Explicit printer
tests cover both company rounding settings, tax/discount totals, nullable dates/rates,
missing row amounts, known/unknown voucher references, dimensions, list immutability,
empty/populated rendering and header voucher maxima. Assembled/packaged smoke creates
fresh normalized invoices and credits, then exports both preview and commit journals.
The normalized credit-creation path uses an explicit-company unbooked-credit constructor:
it copies the original sale, resets entered/printed state and defers voucher generation
until the journal, after any credit-amount scaling. This avoids the legacy constructor's
eager singleton-backed booking calculation. Legacy construction is unchanged.

Supplier invoice/credit journals (`supplier-invoice journal --output` and
`supplier-credit-invoice journal --output`) also use explicit company/hydrated-year,
planned invoice snapshots and dimension lists. Both outer and nested voucher printers
reuse `SSJournalRowContext` and avoid singleton-backed voucher-row lookup helpers.
Input lists are copied before sorting; unknown account/dimension references remain
blank. Totals retain supplier-specific stored tax and rounding amounts, rather than
customer-sale company rounding. Nullable tax/rounding retain the existing zero fallback;
invoice-currency conversion and existing titles/templates/metadata are unchanged.
PDF export still precedes booking writes. Tests cover exact before/after-migration PDF
text, duplicate document numbers across companies, previews, overwrite failures leaving
documents unentered and successful commit exports. Explicit printer tests check stored
amounts, null dates/rates/amounts, voucher references/dimensions, caller list preservation,
header maxima and empty/populated PDFs. Assembled/packaged smoke creates fresh normalized
supplier invoices/partial credits and exports both preview and commit journals.

Customer invoice/credit register PDFs (`invoice list --output` and
`credit-invoice list --output`) use explicit company-wide headers (last voucher zero),
company rounding and stored sale-row snapshots for both outer and nested printers.
They do not require an accounting year. Lists are copied before sorting. Existing
stored descriptions, product numbers, quantities, units, prices, discounts, row sums,
tax, totals, direct currency-rate multiplication and templates are retained. No new
missing-rate fallback is introduced: aggregate columns retain the legacy failure on
null currency rates. Invoice `--from`/`--to` remains inclusive and still includes
undated invoices; the credit register retains its existing unfiltered behavior.
Tests compare exact legacy/normalized PDF text for populated/empty/no-year companies,
filtered and outside-date lists (including undated records), duplicate document numbers
in another company, overwrite protection and singleton isolation. Printer tests cover
snapshot fields, discount/tax totals, both company rounding settings, input preservation
and empty/populated rendering. Assembled/packaged smoke includes both register exports.
Supplier invoice/credit register PDFs also use explicit company/document snapshots
without requiring a year; `supplierInvoiceListPdfsMatchLegacyForEmptyAndNoYearCompanies`
already covers populated/empty companies, isolation, overwrite protection and exact
legacy/normalized PDF text parity. Both exports are included in the packaged smoke.

Other unported PDF commands remain unsupported on normalized storage. This slice
does not activate normalized storage in the GUI or retire legacy persistence;
issue #75 remains open.

### Explicit invoice and payment hydration

The four normalized document stores now hydrate customer invoices/credits, supplier
invoices/credits, inpayments and outpayments through dedicated `forPersistence()`
factories. These initialize empty row/default-account containers without invoking
selected-company lookups, today's date, default addresses, currency defaults or
synthetic voucher headers. Readers then populate the explicit SQL fields and only
restore voucher/correction/difference snapshots when their header rows exist.
Nullable invoice types are assigned explicitly, including SQL null.

This is a hydration boundary, not a change to legacy business initialization.
Public no-argument constructors still create their existing defaults. Lazy business
getters also remain unchanged: use `getStoredType()`, `getStoredVoucher()` and
`getStoredDifference()` where persistence absence matters rather than requesting a
business default or generating a voucher. Document copies now preserve null
voucher/correction/difference snapshots and sale addresses, while still deep-copying
present values. This avoids null dereferences when normalized unbooked invoices
are used to create credits. Explicit supplier and payment voucher calculations
treat an absent correction/difference as zero rows and zero adjustment, without
creating or storing an empty auxiliary snapshot. Copy constructors otherwise retain their existing
business initialization; they and no-argument entity lookups are not generally
isolated from legacy state. Normalized
GUI activation remains disabled; issue #75 is not complete.

`NormalizedDocumentHydrationTest` blocks every legacy JDBC call while inserting
and loading all six document kinds, verifies nullable fields, absent addresses and
snapshots, then books and reloads explicit main/payment-difference snapshots with
null dates. It also guards the existing legacy constructor snapshot defaults. The
regression fails against the old store constructors on a legacy JDBC lookup;
existing rich round-trip fixtures continue covering populated rows, dimensions,
registers and correction snapshots. Additional copy tests cover absent optional
state and independent copies of present snapshots; the packaged CLI smoke covers
credit creation from unbooked normalized invoices. `ExplicitVoucherContextTest`
compares absent auxiliary snapshots against empty ones for both supplier document
kinds and both payment kinds, with legacy JDBC blocked, and verifies the absent
snapshots remain absent after booking calculations.

### Explicit customer, supplier and product hydration

`NormalizedRegisterStore` uses dedicated `forPersistence()` factories for customers,
suppliers and products. They skip selected-company lookups and default addresses,
prices, tax codes, stock flags, units and product accounts; product containers are
initialized empty. SQL fields and stored child rows alone populate the snapshots.
Absent party-address rows stay null and nullable product tax codes stay null; an
empty product account map does not inherit the selected company's Sales/Purchases
entries. This is a hydration boundary, not normalized GUI activation.

Legacy no-argument constructors still initialize their original business defaults.
Customer/supplier copies and sale customer assignment preserve missing addresses
and deep-copy present ones. Existing lazy currency/product business getters are
unchanged: persistence paths must continue using the `getStored...` accessors.
Other constructor, lookup and purchase-order paths are not generally isolated
from legacy state by this slice.

`NormalizedRegisterHydrationTest` blocks every legacy JDBC call while round-tripping
empty and populated snapshots, confirms absence survives a read/write cycle without
adding child rows, and covers company-scoped addresses, account entries (including
explicit null), translations, components, lookups and dimensions. It also checks
legacy constructor defaults and null-safe independent party-address copies/customer
assignment to invoices. Existing full register and migration/PDF parity fixtures
remain in place.

### Complete normalized orders and quotations

`NormalizedSaleDocumentStore` loads and edits complete `SSOrder`/`SSTender`
snapshots from the existing V15 tables: every header column, ordered rows, both
optional addresses, default-account entries (including explicit nulls), lookup
references and document-number links. `forPersistence()` factories skip legacy
company/clock/address initialization. Raw stored currency-rate accessors and
`setStoredTaxRates` avoid business fallbacks; invoice hydration also uses the latter
so a SQL-null second tax rate is not replaced with 12%.

The repository returns `Stored<T>(id, legacyId, document)`. Edits/deletes use the
storage identity plus company scope, not the displayed number: migrated numbers
can be null or duplicated. Updates retain both SQL and migration identities.
New migration identities are allocated across all companies in the relevant table;
document numbers and company counters remain caller-owned and are not advanced.
Reference numbers are snapshots, not instructions to load other entities through
SSDB. Existing no-argument business constructors/getters retain their behavior.

Insertion, replacement (including child removal) and deletion run within a local
savepoint. Auto-commit must be disabled; the store never commits or closes the
connection. A late child constraint or parent-delete failure rolls back only the
operation, preserving earlier caller changes and enabling retry. Successful writes
also remain subject to the caller's commit/rollback. Missing/foreign-company
identities are rejected before any owned children can be removed. The connection
and transaction must be exclusively managed by the caller, as for other normalized
stores; this is not a concurrent identity-allocation service.

Tests compare the migration's complete semantic fingerprint before and after
loading/re-saving populated orders and quotations with legacy JDBC blocked. They
cover null/duplicate numbers, detached snapshots, optional-value absence, ordered
blank/populated rows, foreign-company isolation, child removal/replacement, late
insert/update/delete failures and retry, outer rollback, auto-commit rejection,
and shutdown/reopen on a file database without legacy domain tables. A separate
assembled-JAR probe also checks insertion, editing, late rollback, null preservation,
shutdown/reopen and deletion using the packaged schema resources and classes.
The full packaged CLI smoke remains unchanged and passes.

This is the typed repository prerequisite for later GUI work, not an activated
order/quotation GUI or new CLI command. GUI startup still rejects format 3 and
other editable domains/business lookup paths still require porting. The existing
sales-values projection remains separate and read-only.

### Complete normalized purchase orders

`NormalizedPurchaseOrderStore` hydrates and edits every field of the existing V16
purchase-order header and its addresses, default accounts and ordered rows. It
loads through `SSPurchaseOrder.forPersistence()` without selected-company, address,
clock or stock defaults. Persisted supplier/currency/terms/unit references are
restored explicitly; invoice links stay stored numbers, not singleton lookups.
`getStoredCurrencyRate()` preserves SQL null without applying the business rate
fallback. Missing addresses and empty containers stay absent/empty.

As with the order/quotation repository, `Stored(id, legacyId, document)` separates
stable storage identities from nullable or duplicate historical document numbers.
Company-scoped edits preserve both identities, replace all children atomically,
and reject foreign-company/missing identities before touching children. New legacy
identities are allocated across the whole purchase-order table; numbers and company
counters remain caller-owned. The caller must exclusively manage the connection
and transaction; this is not a concurrent numbering/allocation service.

Writes require auto-commit disabled and use local savepoints. Neither success nor
failure commits or closes the caller's connection. A late row constraint or
parent-delete failure rolls back only that operation, preserving earlier caller
changes and savepoints for retry or later rollback. HSQLDB discards the local
savepoint on rollback, so it is released explicitly only on success.

Purchase-order copies preserve nullable addresses and independently copy present
addresses, rows and default-account maps. They also now retain the stock-influencing
flag (previously omitted). Assigning a supplier tolerates an absent supplier address;
other supplier/business resolution behavior remains unchanged and is not generally
isolated from the legacy singleton by this slice. No-argument business constructors
and currency-rate fallbacks remain unchanged.

Tests block legacy JDBC while exercising complete migration fingerprint parity,
all fields/children, optional absence, duplicate/null numbers, identity retention,
company isolation, detached snapshots, replacement with empty children, late
insert/update/delete failures, retry, preserved outer savepoints, auto-commit
rejection, independent copies and file database shutdown/reopen without legacy
domain tables. This is a typed storage prerequisite only, not GUI activation or
new purchase-order CLI commands. A separate assembled-JAR probe verifies purchase-order
CRUD, late rollback/retry, copy flags, null preservation and file shutdown/reopen;
the full assembled-JAR CLI smoke also passes. GUI startup still rejects format 3.

### Complete normalized stock documents

`NormalizedStockDocumentStore` loads and edits complete inventory counts, incoming
deliveries and outgoing deliveries from the existing V17 header/row tables. The
three model `forPersistence()` factories initialize only empty rows, bypassing
legacy numbering, date and text defaults. Readers restore nullable numbers/dates/
texts, ordered product references and signed changes, plus inventory stock counts;
null, zero and negative quantities remain distinct. Unregistered product numbers
remain snapshot references rather than triggering implicit product lookups.

`Stored<T>(id, legacyId, document)` separates stable SQL/migration identities from
nullable or duplicate historical numbers. Reads, replacements and deletion are
company-scoped through the parent header, including row tables with no company
column. Updates retain both identities; new legacy identities are allocated across
the relevant table, not just the selected company. Numbers/counters remain
caller-owned. Returned models are detached, editable snapshots, not write-through
objects. Legacy constructors still auto-number and initialize their original
business defaults; copy constructors still independently copy their rows.

Each write requires auto-commit disabled and runs inside a local savepoint. The
caller exclusively manages the connection and outer transaction; this repository
never commits/closes it or implements concurrent numbering. Late row-write or
parent-delete failures restore only the failed operation, preserving prior work
and outer savepoints for retry/rollback. Successful writes also remain subject to
the caller's rollback. HSQLDB discards the local savepoint on rollback, so explicit
release is performed only on success.

Tests block every legacy JDBC call while comparing complete migration fingerprints
after loading/re-saving all three types, checking null/duplicate numbers, ordered
nullable rows, detached snapshots, company isolation, empty replacement, late
insert/update/delete failures, retry, outer savepoints and auto-commit rejection.
File shutdown/reopen tests need no legacy domain tables. Integration tests verify
that the existing `NormalizedStockStore` projection reflects typed writes, signed
changes and deletion, excludes other companies and returns to committed totals on
caller rollback. This adds the editable-document repository, not a GUI/CLI entry
point; the existing stock projection remains read-only and GUI startup still
rejects format 3. A separate assembled-JAR probe verifies all three document types,
late rollback/retry, nullable headers, stock totals, deletion and file reopen; the
full assembled-JAR CLI smoke also passes.

### V20 periodic-invoice snapshot preservation and compatibility boundary

V11's original periodic fingerprint covered a flattened header/row projection,
not every nested invoice snapshot. `V20__preserve_periodic_invoice_snapshots.sql`
adds six explicit tables without editing any installed migration checksum.
`PeriodicInvoiceStagingConverter` now preserves and fingerprints template presence,
template/instance invoice and delivery addresses, default-account entries,
voucher headers and ordered rows (including dimensions/edit metadata/flags), and
the raw added-state map. Null, false, true and absent map entries stay distinct;
orphan/null keys survive. Nested owners use template index -1 or generated-instance
ordinal, never invoice number, so duplicate or null instance numbers do not merge
snapshots. An absent template/voucher remains distinct from an empty one.

Fingerprints retain all V11 domains and add six independently named nested domains.
Canonical decimals follow the existing scale-30 policy; voucher edit timestamps use
the existing Swedish legacy-time resolver and retain nanoseconds in V20. These
domains participate in the existing staged/reopened verification chain. A late
nested write or fingerprint mismatch rolls back the entire periodic conversion,
not just its new child rows. Company deletion now removes the complete graph with
foreign keys enabled (97-table ownership matrix) and still supports old V19 graphs.
Unsupported invoice subclasses and correction-linked voucher object graphs are
explicitly rejected with `PERIODIC_SNAPSHOT_UNSUPPORTED`, rather than discarding
subtype fields or referenced vouchers. They require further representation work.

Compatibility is deliberately fail-closed: existing normalized CLI opening does
not apply new migrations, and V20 has **no data backfill**. Merely adding empty
V20 tables cannot restore lost legacy values or establish completeness. Complete
periodic fingerprinting requires all V20 tables/history plus explicit template and
instance markers, and rejects inconsistent graphs with
`PERIODIC_SNAPSHOT_RECOVERY_REQUIRED`. Existing V19 non-periodic CLI paths remain
unchanged. New legacy migrations populate and verify these records from the source.

There is no automatic recovery/upgrade of an already-normalized periodic projection.
Preserve its current database and verified legacy rollback artifacts; do not replace
current accounting data with an old backup, reconstruct addresses from today's
registers, or fabricate empty snapshots. The explicit V19 recovery command below
preserves post-migration data under strict identity/projection checks. Neither a
periodic editing repository nor normalized GUI activation is enabled by these changes.

Regression fixtures cover every new fingerprint domain, null/empty distinctions,
raw map entries, duplicate instance numbers, nanosecond/DST edit metadata, late
failure rollback, tampering, missing/partial markers, old V19 and schema-only V20,
unsupported graph rejection and durable file reopen. A separate assembled-JAR
probe exercises real legacy file migration through staging, activation, rollback
archive creation and two reopens, comparing the complete periodic fingerprint and
nested table contents. The full assembled-JAR CLI smoke also passes. Existing
catalogue recovery continues to apply only V18-to-V19; its historical fixture
remains genuinely V18.

### Explicit recovery for existing V19 periodic projections

Close Bokfri and every process using the data directory, independently back it up,
and verify the provenance of its legacy rollback ZIP. A SHA-256 proves integrity,
not that the archive belongs to this database or is safe to deserialize. Recovery
is an explicit operator decision, never automatic source selection:

```sh
bokfri --data-dir /path/to/data database recover-periodic-invoices \
  --trusted-legacy-backup /path/to/verified-legacy.zip \
  --sha256 EXPECTED_64_CHARACTER_SHA256 --commit
```

`NormalizedPeriodicRecoveryService` requires exactly normalized format 3/schema V19,
without any partial/existing V20 snapshot tables. It refuses unresolved staging,
locked/dirty catalogues and unsupported SQL types that cannot be safely fingerprinted.
Missing `--commit` performs no recovery. Saved GUI company/year selection is unchanged.
V20 databases, including manually added empty V20 tables, are not overwritten or
repaired by this command; they require separate manual assessment.

The service freezes and re-hashes the trusted ZIP, copies the **current normalized
database**, extracts only permitted catalogue entries into an isolated writable
legacy copy, and deserializes only periodic rows whose legacy identities still
exist in the current database. Deleted source records are not resurrected and their
unsupported nested graphs do not block unrelated surviving records. Every selected
legacy identity/company, periodic/template header, ordered row, generated instance
and effective added flag must match the current complete V11 projection. Missing,
changed or mismatched records are refused; document numbers alone are never used
as identities. The operator's provenance check remains essential where different
archives could have identical projections but different omitted snapshots.

Only then is V20 applied in staging and its nested tables populated. All original
PUBLIC table schemas/rows, including account-plan catalogues, accounting entered
after normalization, evolved typed tables and schema-history rows through V19, are
hashed and verified unchanged. Unrelated domains are not reconstructed from legacy.
The complete periodic fingerprint and original-table hashes are checked before
activation, after disk reopen and again after activation. A final active-file hash
check detects intervening changes before any active files are moved.

Activation retains the pre-recovery **current normalized** catalogue and a rollback
ZIP. The trusted source ZIP stays unchanged. Failures before activation leave the
active catalogue untouched and retain staging for inspection; there is no automatic
retry or abandonment cleanup. HSQLDB can commit schema DDL, but failed nested writes
are rolled back in their staging transaction and never activated. Existing V18
account-plan recovery remains specifically V18-to-V19, not permission to upgrade
or overwrite other domains.

Regression tests cover real post-migration vouchers, evolved decimal/binary tables,
physical rollback equality, two reopens, changed/missing projections, deleted
unsupported objects, unsupported surviving graphs, late staging failures, pending
recovery state, unsafe sources/types, dirty catalogues and unchanged GUI selection.
A separate assembled-JAR CLI probe verifies explicit commit, V19-to-V20 recovery,
all original SQL rows, rollback artifacts and two reopens; full CLI smoke passes.

## Customer invoice and credit editing boundary

`NormalizedInvoiceStore` now exposes stable-ID `list`/`find`, insertion returning
its SQL identity, in-place update and owned-graph deletion for customer invoices
and credits. SQL `id` and migration `legacy_id` survive replacement; neither
nullable nor duplicated historical document numbers are used as edit identities.
Existing document-only list methods remain available. Absent addresses/vouchers,
raw null tax rates, nullable default-account values, ordered sale rows and voucher
metadata survive loading and saving. A JDBC `wasNull()` ordering bug that converted
null default-account values to zero is fixed for both document families.

Every write, including existing insertion and entered-state marking, requires
auto-commit disabled and uses a local savepoint. A late SQL or runtime failure
restores only that operation; earlier caller work and savepoints remain usable.
The repository never commits/closes the connection, increments business counters,
allocates document numbers or mutates ledger vouchers. Legacy-ID exhaustion is
rejected instead of overflowing. Existing number-based booking refuses missing or
ambiguous numbers before changing any row, and snapshot replacement is atomic.
Unsupported invoice subtypes and correction-linked embedded voucher graphs are
rejected rather than partially stored.

General update/delete is deliberately limited to **unbooked** documents; editing
cannot set the entered flag. Invoice renumber/delete refuses company-scoped
inpayment, credit and order references, even for zero-valued payments. Renumbering
also protects the proposed number, and insertion refuses adopting dangling
references. An unchanged number preserves its scalar links; duplicate target
numbers cannot be introduced by renumbering. Historical duplicate/null numbers
remain readable and can be addressed by SQL identity. No other company's
references block an operation. The repository does not automatically detach orders,
rewrite payments/credits or validate every possible business relationship.

This is not complete legacy GUI parity: the old invoice/credit dialogs can write
through legacy services, and invoice-frame deletion explicitly clears order links.
Those actions, booked metadata/correction, journal transactions, numbering and
cache notifications still require a shared application workflow. Format-3 GUI
startup remains disabled. Track these remaining operations in the
[cutover checklist](normalized-cutover-checklist.md), rather than marking the
whole invoice domain complete.

Regression tests cover full migration/load/resave fingerprints with legacy JDBC
blocked, company isolation, stable identities, null/empty snapshots, reference
protection, booked-document refusal, ambiguous booking numbers, unsupported graphs,
late insert/update/delete/booking failures, caller rollback/retry and two file
reopens. The full assembled-JAR CLI smoke also passes.

## Atomic normalized customer journal workflow

`NormalizedCustomerJournalService` is the shared invoice/credit journal boundary.
Planning reads the explicit company/year, accounts and dimensions, selects unbooked
documents using the existing CLI period rules, and generates detached document and
compressed journal vouchers. Posting re-reads and regenerates that data rather than
trusting mutable preview objects. An opaque token fingerprints selected SQL and
migration identities, numbers/dates, per-document accounting vouchers, journal
counter and next ledger number. Changed financial output, selection, counters or
number allocation refuse posting and require a fresh preview. Non-posting metadata
can change without invalidating the token; fresh stored values are retained.

A single local savepoint encloses all selected document voucher snapshots and
entered flags, the complete ledger voucher/rows, and the company journal counter.
Late document, ledger or counter failures restore the whole journal while
preserving earlier caller work and outer savepoints. Stable-ID entered-state
writes refuse wrong-company, missing or already-booked records. The reusable
service never commits/closes the connection; both normalized CLI journal commands
now call it and commit once on success. Exclusive application ownership remains
required; this is not a concurrent numbering implementation.

Missing/ambiguous selected document numbers, exhausted counters/ledger numbering,
missing nonzero sales-row accounts and per-document/journal imbalances fail closed.
Two opposite document errors cannot merely cancel into a balanced combined journal.
The injected clock supplies generated snapshot dates in `Europe/Stockholm`; the
ledger date remains the requested journal end date. A nullable persisted invoice
type uses its normal business fallback only on detached posting objects; posting
never fills in the raw header or missing historical default accounts.

Normalized CLI invoice creation now uses `CustomerInvoiceFactory` with explicit
company/date defaults instead of a legacy-selected-company constructor. This fixes
new invoices losing account/tax defaults when the legacy singleton has no company.
It does **not** backfill existing incomplete snapshots: operators must correct
unbooked input explicitly if it cannot produce a balanced journal. Legacy GUI
creation, journal paths and CLI response shapes remain unchanged.

Tests cover invoice/credit totals, scope and dimensions with legacy JDBC blocked,
late failures in each write phase, caller rollback/retry, stale finance/selection/
counters/ledger numbers, mutable preview tampering, metadata preservation,
nullable header preservation, explicit creation defaults and two disk reopens.
Full CLI integration tests and assembled-JAR smoke include customer invoice and
credit journal commits. Booked metadata/correction, order detachment and GUI
adapters remain open in the [cutover ledger](normalized-cutover-checklist.md).

## Guarded customer metadata and explicit order detachment

`NormalizedCustomerDocumentService` separates non-posting document state from
financial graph replacement. Invoice and credit metadata APIs use stable SQL ID
plus company scope, and update only `printed`, `ocr_number`, `reminder_count` and
`interest_invoiced`. They work on booked documents without rewriting entered flags,
amounts, dates, addresses, ordered rows, default accounts, voucher snapshots or
ledger records. Four expected metadata values must still match the stored values;
null and empty OCR values remain distinct. Missing/wrong-company/stale updates
are refused. Negative replacement reminder counts and increment overflow fail
without changes. Historical negative counts can be read for explicit repair.

The optimistic comparison is value-based, not a new row-version/event receipt.
Callers own print/PDF success handling and any full interest-invoice generation
transaction; these APIs do not establish exactly-once external printing or by
themselves create interest invoices. Existing CLI PDF commands stay read-only,
legacy GUI paths stay unchanged, and there are no new CLI commands in this slice.

`deleteInvoiceWithOrderDetachment` coordinates the legacy invoice-frame sequence
as one SQL operation: load an unbooked invoice by stable identity, explicitly clear
only matching company-scoped order `invoice_number` fields, then delete its owned
graph through the guarded repository. Order identities and every other order
header/child value remain unchanged. Booked invoices remain undeletable; payment
and credit references remain protective and are never rewritten. If they cause
refusal after detachment, or a later child/parent delete fails, the outer savepoint
restores both invoice data and all order links. Prior caller work/savepoints are
preserved, and successful operations still require the caller's commit.

A null invoice number designates no order link. If linked orders exist for a
number shared by multiple historical invoices in the company, the service refuses
to guess ownership. Other-company links never detach or block the operation.
The lower-level order helper changes only the link column, not a hydrated/re-saved
order graph, and still requires a caller-owned transaction and exclusive access.

Regression tests compare every PUBLIC table while excluding only specifically
permitted metadata/link/deleted-graph differences. They cover booked invoice and
credit metadata, untouched financial and other-company data, stale/null/empty OCR,
reminder limits, late SQL failures, caller rollback/retry, ambiguity, payment/credit
and booked guards, posting interoperability with metadata changes, blocked legacy
JDBC and two disk reopens. GUI adapters and financial correction workflows remain
open in the [cutover ledger](normalized-cutover-checklist.md); metadata support is
not permission to replace a booked document's financial graph.

## Explicit booked customer-invoice credit corrections

`NormalizedCustomerCreditService` plans by company/year and stable original SQL
identity, then creates an **unbooked** credit under one savepoint. It never changes
the booked original, embedded original voucher, ledger, payments or earlier credits.
The existing customer credit journal service remains the separate explicit posting
step; creation alone does not add ledger rows or mark the new credit entered.

The original must be booked, non-cash and have a unique non-null invoice number
within its company, because payment/credit references remain number-based.
All existing credits, including unbooked reservations, and inpayments reduce the
available balance using the existing invoice-balance calculation (two decimals,
HALF_UP). Explicit amounts must be positive and not exceed that balance. The
reusable service also accepts an omitted amount to mean the **remaining** balance,
not a blind full-total copy after partial payments/credits. Existing CLI input
schema still requires explicit date and amount; no input requirements or command
paths change in this slice.

Proportional credits retain the existing scale-12 HALF_UP unit-price algorithm,
including source tax/discount/currency/dimension snapshots. Non-monetary text rows
with null prices survive. The resulting monetary value must be positive, fit the
available balance and agree with the request at the existing two-decimal balance
precision. If whole-unit company rounding or another representation constraint
prevents that, the service refuses creation instead of silently over-crediting or
inventing adjustment lines. Raw stored decimals are not globally rounded to cents.
An explicit-account voucher preview also refuses missing nonzero sales accounts
and unbalanced results. It is never persisted as a speculative booked snapshot.

An opaque, non-durable token covers the complete original SQL graph, proposed
number/date/value, available balance, relevant company policy/text and accounting
preview. Creation re-reads/regenerates those inputs and rejects stale source,
reservation or numbering changes. Even source metadata changes invalidate this
copy token, since those fields are copied into a new document. Mutable presentation
snapshots are ignored as creation inputs. No Java-object serialization is used;
source fingerprinting reads only known typed SQL columns and refuses unknown types.

The next credit number is one above the maximum existing company credit number or
configured counter floor, with overflow rejection. The floor remains unchanged,
as in legacy insertion; there is no unrelated counter bump or new concurrent
allocator. Number derivation, graph insertion and monetary readback verification
are in the caller-owned transaction. Late failures restore the entire new graph,
retain earlier caller work/savepoints and permit retry without consuming a number.
Exclusive application ownership remains required.

The normalized CLI validate/create paths now use this service, with one final
commit on creation. Legacy paths and response keys remain unchanged. A normalized
unbooked credit's absent voucher is rendered as an empty row list, without invoking
the legacy lazy voucher generator. Stored original snapshots are never backfilled
or rewritten to make an unsupported correction appear valid.

Tests cover all-table original/ledger preservation, partial payments and existing
credit reservations, full remaining/partial amounts, stale source/reservation/
numbering, preview tampering, late insert rollback/retry, caller savepoints,
company/year isolation, number floors/overflow, rounding refusals, blocked legacy
JDBC, explicit credit journal posting and two reopens. CLI integration exercises
partial payment → credit creation → separate posting and duplicate-credit refusal.
General booked graph editing, arbitrary voucher correction links and GUI adapter
activation remain outside this supported credit path; see the
[cutover ledger](normalized-cutover-checklist.md).

## Explicit normalized credit-request GUI entry point

`CustomerCreditDialogAdapter` binds a GUI request to explicit company/year/source
identities, a fresh-connection factory, an injected clock and the application-owned
transaction gate shared by all writers for that database. No legacy singleton or
selected-global context supplies these dependencies. Each action owns a dedicated
short-lived JDBC session starting in auto-commit mode, then manages its own
transaction; borrowed caller transactions are not supported. Preview rolls back
its read session, save calls the shared credit service and commits once. There is
no implicit `SHUTDOWN` of the application's database or reuse of `startupLocal`.

The adapter first requires exactly format 3 and validates installed sequential
migration names/checksums through the read-only
`SchemaMigrationRunner.validateInstalled` entry point. Credit dependencies require
at least V12. Missing, incomplete, tampered or future history is refused, never
created, upgraded or repaired in the dialog. This does not establish that an old
partial schema is ready for the rest of the GUI.

Preview presentation values are immutable and carry a private workflow token;
source/balance/number changes are revalidated at save. A confirmed result is cached
for that preview, so duplicate clicks/calls cannot insert it twice in one dialog.
A close error after a confirmed commit is logged, not reported as a failed write.
A commit exception, whether thrown before or after durability, has an unknown
outcome: the adapter blocks further preview/save requests and the UI directs the
user to close and refresh the owning context. This cache is not a durable receipt
or general concurrent allocator. Shared application gating and exclusive database
ownership still apply; callers must supply the same gate to other writers.

`SSCreditInvoiceDialog.normalizedDialog` builds a separate restricted request form:
ISO credit date and optional amount (blank means remaining balance), preview, and
explicit create/cancel. It intentionally does not feed arbitrary legacy editor
row/customer/product mutations into the request service. Neither construction nor
cancel/ordinary close writes data, and closing does not auto-save. Backend work
runs in `SwingWorker`, never on the Swing event thread; input changes invalidate
the preview, and even programmatic changes during a pending preview cannot enable
a stale save. Close/Escape/cancel are blocked while an action is in flight.
Confirmed refresh notification runs on the EDT only after commit, followed by
disposal. A refresh callback failure is logged as a notification failure, not a
reason to retry an already committed credit.

`CustomerDocumentGuiSession` supplies explicit company/year IDs, a fresh connection
factory, clock and shared gate. Its read-only selector lists booked, non-cash invoices
with unique company-wide numbers; credit requests retain SQL identity, not a legacy row.
`CustomerCreditMenuBinding.install` (on the EDT) binds that selection to an owner frame.
The invoice Credit and credit-invoice New actions route through it before consulting
legacy selection. Unbound owners retain legacy behavior. Source loading and requests
run off the EDT; confirmed notifications carry company/year/source IDs. Replacement
closes the old session, invalidates dialogs and suppresses stale-selection refreshes;
owner disposal also invalidates the session. Closed installed bindings consume actions
rather than falling back to legacy storage. Labels/messages are resource-bundle keys.

This is an explicit integration boundary, **not normalized GUI activation**. Production
format-3 bootstrap, application-wide GUI/CLI ownership and replacement of the legacy
screen/cache paths remain open. Startup still rejects format 3; other screens, full credit-frame
operations and arbitrary legacy financial editing are not made safe by this adapter.

Tests exercise read-only preview, dedicated session closure, confirmed replay,
stale/foreign previews, format/history refusal without migration, insert rollback/
retry, uncertain commits on both sides of durability, post-commit close failure,
shared-gate serialization, headless form validation/invalidation and EDT callbacks.
A real Xvfb dialog test blocks legacy JDBC, checks background execution and busy
close/Escape handling, then verifies one committed credit and one EDT table refresh.
Session tests additionally cover explicit selection, source filtering, lifecycle invalidation
and in-flight commit ownership. Xvfb menu tests cover stable-ID source selection through
confirmed creation, qualified callbacks, selection replacement, legacy fallback only for
unbound owners and toolbar enablement independent of legacy row selection.
The [cutover ledger](normalized-cutover-checklist.md) records remaining root wiring.

### Explicit customer-document view and metadata dialog

`CustomerDocumentView(owner, session)` is an EDT-created, opt-in normalized panel; it
installs the owner's credit-menu binding and displays separate invoice and credit tables.
Its overview table cells remain read-only; explicit actions support metadata editing,
bounded unbooked financial editing, confirmed deletion and customer journal posting as
described below. It does not instantiate legacy frames/models,
call `SSDB` caches, allow arbitrary financial editing, or bypass the format-3 startup guard. The selected year is posting context; document lists
are company-wide, matching the existing document scope rather than filtering by year dates.

`session.documents()` reads both lists in one dedicated transaction under the shared
application gate and returns immutable values with stable SQL IDs. It explicitly uses the
selected company's rounding policy. Invoice balances subtract payments and credits just
as the normalized CLI does; cash invoices have zero balance. Missing/duplicated invoice
numbers display an unknown (blank) balance instead of assigning number-based references
to an arbitrary row. Credit rows show their credited invoice number. Currency snapshots
may be absent; the view does not invent defaults. Reads roll back and close their lease.

The view loads off-EDT and replaces both table models before notifying EDT listeners.
A confirmed credit notification for its company/year reloads both tables; foreign-context
notifications do nothing. Sorting does not change identity: row selection is restored by
SQL ID, never old row index or business number. Refresh failure retains the last successful
snapshot and exposes an error with retry rather than presenting a false empty result.
Selection replacement clears prior rows immediately; generation/session checks discard
late loads from replaced or closed selections. Closing the view invalidates its own installed
binding, clears rows, and prevents legacy fallback, without closing a newer owner's binding.

Tests cover immutable/read-only snapshots, explicit rounding, payment/credit balance parity,
cash/ambiguous numbers, connection closure and format/selection refusal. Xvfb tests cover a
real source-selector/request-dialog commit updating both tables with legacy JDBC blocked,
stable selection after sorting/reordering, failed refresh/retry, foreign notifications,
cross-company replacement during a blocked load, and close during reload. These are evidence
for this restricted view, not full legacy invoice/credit editor or application GUI parity.

### Guarded customer-document metadata editing

`session.metadata(kind, sqlId)` binds a `CustomerDocumentMetadataAdapter` to the explicit
company/year, document kind and stable SQL identity. It reads only the four supported
metadata fields and calls `NormalizedCustomerDocumentService` to update printed status,
OCR, reminder count and interest-invoiced status. No dates, amounts, entered flags, document
rows, voucher snapshots or ledger data are changed. Booked documents remain booked.

Each load/save uses a dedicated, format/history-validated connection under the same gate
as credit creation. Reads roll back; writes commit only after a successful four-field
compare-and-swap. Foreign adapter snapshots and stale expected values are rejected. A
confirmed identical replay returns the original result without writing again; a different
replacement requires a new load. Ordinary failed updates roll back and can be retried.
A commit exception blocks further requests on that adapter because durability is unknown;
a connection-close failure after a confirmed commit does not turn success into a failed save.

The view's invoice and credit metadata actions resolve sorted table selection to SQL ID
and document kind before opening `CustomerDocumentMetadataDialog`. Its title identifies
the target and company/year. Load/save run off-EDT; close/Escape/cancel are blocked while
busy. The form preserves null OCR separately from the empty string, validates nonnegative
reminder counts and offers an explicit reload after stale-data failure. Successful save
closes the dialog and refreshes only the still-current view on the EDT. Replacement/close
invalidates metadata dialogs; an already accepted write may finish in its original context,
but cannot dispatch a stale-context refresh. Unknown commit outcomes disable save/reload
and require closing/refreshing the external context before another attempt.

Tests compare every SQL table excluding only the intended four metadata columns, check
stale/foreign/closed-context and format refusal, late-update rollback/retry, shared gating
with credit creation, replay and unknown commits before/after durability. Xvfb tests cover
both invoice and credit routes (including overlapping SQL IDs), legacy-JDBC refusal,
commit-confirmed refresh, busy-close protection, reload and OCR null/empty handling,
selection invalidation during accepted save and uncertain-commit UI blocking. This remains
a restricted metadata adapter, not the legacy financial editor or production GUI cutover.

### Confirmed unbooked invoice/credit deletion

`session.deletion(kind, sqlId)` supplies an owner-bound `CustomerDocumentDeletionAdapter`
for the explicitly selected company/year and stable document identity. The read-only plan
from `NormalizedCustomerDocumentDeletionService` presents kind, number, customer, date,
SQL ID and the exact order IDs whose invoice links will be cleared. Booked documents,
invoice payment/credit references, and ambiguous invoice numbers owning linked orders
are refused. Null or duplicated numbers without references still target only the selected
SQL identity, never an arbitrary business-number match.

The ephemeral plan hashes every typed scalar column in all six source-document tables,
plus the linked-order identity list. Row-signature sorting preserves multiplicity without
relying on JDBC row order; unsupported SQL types fail closed. Execution re-plans under
the shared application gate, rejects changed source snapshots/order sets or new references,
and uses a savepoint around guarded deletion. Invoice deletion delegates to the existing
atomic order-detachment workflow; credit deletion leaves its original invoice intact.
Caller-owned commit and fresh connection lifecycle are shared with the metadata adapter
via `CustomerDocumentTransactions`, including rollback/retry, uncertain-outcome blocking
and confirmed-success handling after a connection-close failure.

`CustomerDocumentDeletionDialog` requires a loaded preview and a separate confirmation
checkbox before enabling deletion. Loading/deleting runs off-EDT. Busy close/Escape/cancel
are blocked; stale/failing attempts require reload and renewed confirmation. Confirmed
success closes the dialog and refreshes both tables only in the still-current view.
Selection replacement/close invalidates the dialog, allowing accepted work to finish only
in its original context without a stale refresh. Unknown commit outcomes disable deletion
and reload instead of reporting success or permitting a blind retry. Confirmed duplicate
execution of one preview returns its prior result without another write.

Tests compare all PUBLIC SQL tables while excluding only the deleted graph and explicitly
listed order-link columns, and verify rollback restores all tables after a late delete failure.
They cover booked/reference guards, changed row values/order sets, exact SQL identity for
null/duplicate business numbers, foreign previews/selections, format/year refusal, unknown
commits on both sides of durability and post-commit close failure. Xvfb tests use actual view
routes and explicit confirmation with legacy JDBC blocked, test credit removal/balance
refresh, stale reload, busy-close protection, replacement during an accepted deletion and
uncertain-outcome UI blocking. Production bootstrap and financial editors remain separate;
this restricted deletion workflow does not enable format-3 GUI startup.

### Explicit customer journal preview and posting

`session.journal(kind)` binds `CustomerJournalAdapter` to the selected company/year,
clock, connection factory and shared transaction gate. It delegates financial planning
and whole-workflow posting to `NormalizedCustomerJournalService`; it does not duplicate
calculators, numbering or ledger logic. New read-only plan accessors expose stable document
IDs and the actual allocated journal/voucher numbers (the legacy presentation voucher's
placeholder number is not shown as the eventual ledger number).

The adapter copies presentation into immutable document and voucher-row records, preserving
account numbers and string project/result-unit codes (including leading zeroes). It exposes
no mutable domain objects, voucher edits or posting-token construction. A preview rolls back
its dedicated read transaction. Posting revalidates the backend token and changes document
snapshots/entered flags, ledger rows and the counter in one outer commit. Confirmed replay
returns its prior result without posting twice; ordinary failures roll back and can be retried.
Unknown commit outcomes block further requests; a connection-close failure after confirmed
commit does not misreport posting as failed.

The restricted view offers separate invoice and credit journal actions, independent of
legacy table selection. `CustomerJournalDialog` prefills the period from the selected year's
confirmed view context but performs no planning or posting merely on open. Users explicitly
preview an inclusive range, inspect stable document IDs, voucher rows/dimensions, debit/credit
totals and allocated numbers, then tick confirmation before posting. The backend's established
selection rules apply: undated invoices are included, undated credits are excluded. Invalid
or changed dates clear the preview and confirmation; a late result for an older range is
discarded. Stale source/selection/accounting/numbering inputs require a new preview.

All JDBC work runs off-EDT; tables are read-only. Busy close/Escape/cancel are blocked.
Selection replacement/close invalidates dialogs and suppresses stale-context refreshes while
accepted work retains its original company/year. Only confirmed current-context success
refreshes both view tables. Unknown outcomes disable preview/posting and range inputs rather
than allowing a blind repost; external context must be refreshed/reconciled before trying again.

Tests cover immutable read-only previews with actual allocated numbers, invoice and credit
posting, source/dimension preservation, stale financial inputs/document sets/counters, late
counter failure restoring every SQL table, foreign/closed/format/year refusal, replay and
unknown commits before/after durability. Xvfb tests exercise both actual view routes with
legacy JDBC blocked, explicit confirmation and one commit refresh, date input/generation
invalidation, stale re-preview, replacement during an accepted post and uncertain-outcome UI
blocking. These are restricted customer-journal adapters, not production format-3 startup,
other document-family posting adapters or full legacy financial-editor parity.

### Bounded unbooked financial editing

`session.financialEdit(kind, sqlId)` binds `CustomerFinancialEditAdapter` to one explicit
company/year/kind/stable identity. The restricted form changes invoice date, due date,
document tax-exemption, its three stored VAT rates and the stored exchange rate,
and descriptions, quantities/unit prices, discounts, sales accounts and VAT codes on existing
rows. Blank dates/amounts
mean SQL NULL, not a default date, price or zero; description NULL and empty string remain
distinct. Numbers, customers, addresses, document/company currency codes, other tax/EU flags, dimensions,
product/unit snapshots, metadata, order/payment/credit links and
stored voucher snapshots are not replaced. Booked documents are refused. New document
creation and row insertion/deletion/reordering or other financial-field editing remain open.

`NormalizedCustomerFinancialEditService` loads immutable values and real persisted row keys
(not list/display indexes), with an ephemeral token over all six source tables and the
company rounding policy and explicitly selected year account-plan values (including active/required-
dimension flags). It requires the complete unchanged amount row-key set, recomputes totals
using explicit rounding, and rejects stale graphs, metadata or rounding context. The shared
typed fingerprint helper preserves deletion's existing token semantics. Quantities and prices
and discounts must fit DECIMAL(100,30) exactly; changed non-null discounts must be within
0–100 inclusive. Historical out-of-range discounts may be retained unchanged while editing
other fields, but cannot be introduced anew. Dates must fit SQL DATE (years 0001–9999). Unsupported
values fail rather than being silently rounded/coerced. Decimal scales do not trigger needless
row writes when the numeric value is unchanged.

The new `NormalizedInvoiceStore` narrow update methods use a local savepoint, company/SQL-ID
scope and unbooked guard. They update only the two header date columns and changed existing
rows' quantity/unit-price, description/discount and/or account-number/VAT-code columns; no graph reconstruction or
number-based retargeting occurs. Optional immutable `LineDetails` patches use persisted keys;
omitted keys preserve their stored details, and existing amount-only callers remain unchanged.
Optional `LineAccounting` patches likewise preserve omitted keys and use persisted enum names,
not singleton-backed VAT display decoding. Non-null changed accounts must be active 3000–3999
accounts in the explicit selected year without project/result-unit requirements. This bounded
form refuses new assignments requiring dimension editing rather than silently adding dimensions.
Unchanged historical/missing account values are retained; an explicit blank clears an account
in a draft and is not a claim that the draft can be posted. New non-null VAT-code selections
other than zero require a present stored document rate within 0–100. Missing rates are never
filled from company or legacy defaults; unchanged historical codes/rates are preserved.
The source document exemption flag and raw rates are shown separately from editable draft
header fields. Optional immutable `TaxValues` patches contain the non-null exemption flag
(schema `tax_free` is NOT NULL) and nullable exact rates. Existing row-only callers omit the
patch and preserve all header tax columns. Changed non-null rates must be within 0–100;
unchanged historical out-of-range rates remain preservable. For any changed taxable header,
all VAT codes used by the resulting rows must have present bounded stored rates: clearing a
used rate or removing exemption without one is refused, rather than calculating with implicit
25/12/6 defaults. Unused slots may remain absent, and exempt drafts may retain absent rates.
Unchanged historical taxable headers retain prior calculation semantics without storage repair.
A simultaneous row-code/header patch validates against the proposed rates, not the old values.

Optional `ExchangeRateValues` patches change only the stored `currency_rate`, never currency
codes, catalogue rates, linked documents/payments, row prices or amounts. Existing 3–6 argument
`FinancialValues` constructors preserve the rate. Changed rates must fit DECIMAL(100,30)
exactly, be explicit and positive, and have present document/company currency codes; identical
codes require a changed rate of 1. Unchanged historical null/zero/negative/non-unit rates are
retained without repair; clearing a present rate or introducing zero/negative is refused.
Company `currency_code` is the accounting currency (the legacy company panel labels it
Redovisningsvaluta); it is read explicitly and added to the stale token. Catalogue exchange
rates are not calculation inputs, so changing a catalogue default cannot replace a stored rate.
Document totals retain document units. The additional accounting equivalent is document total
times the stored positive rate, without additional rounding; missing rate/context is shown as
unknown, not silently treated as 1 or SEK. This is not a promised journal amount: per-row
conversion/rounding and bookability are independently validated by separate journal preview.
The service revalidates the source, applies those updates, and verifies persisted dates,
amounts, descriptions, discounts, accounts, VAT codes, raw header tax/exchange values and totals against the preview before the caller commits. Late failures restore both
header and row changes. Existing unbooked voucher snapshots remain untouched until the
separate journal workflow regenerates them from the edited rows. This is draft editing, not
an assertion that every draft is bookable; posting still requires its own validated preview.

`CustomerFinancialEditDialog` loads off-EDT, shows readonly stored row keys and explicit
selected-year account/stored-document tax context, and allows dates, descriptions, quantities,
prices, discounts, accounts, VAT codes, exemption and stored VAT/exchange rates to be edited. Rate inputs
accept exact nullable decimals; the VAT-code renderer reflects draft rates without legacy
default/display decoding. Header changes invalidate confirmation and late preview generations
just like row/date edits; reload restores stored values and unknown commits disable every new
tax/exchange input. The exchange control shows readonly document-to-accounting currency units
and a separately labelled unrounded equivalent in preview; the confirmation explicitly includes
the stored exchange rate. Integer account and fixed-code VAT
editors never consult legacy selection or decode displayed percentages. A fresh totals preview and checkbox confirmation
are required before save. Starting or committing a cell edit, changing a date, or reloading
invalidates prior confirmation; invalid/inexact cell input refuses editor commit. Pending
preview results are generation-fenced. Reload explicitly discards the draft. Busy close/Escape/
cancel are blocked; selection replacement invalidates dialogs, accepted work retains original
context and only current-context confirmed success refreshes the view. Confirmed replay is
idempotent; unknown commit outcomes disable editing/reload/preview/save pending reconciliation.

Tests compare every PUBLIC SQL table excluding only the intended target date and row amount
and description/discount/account/VAT-code/header-tax columns. They cover both kinds, non-contiguous row keys, NULL fields, metadata/reference/
voucher preservation, exact decimal limits, stale graph/rounding inputs, bad row-key sets,
booked/foreign/closed/format guards, partial-write rollback/retry, unknown commits on either
side of durability and confirmed close failure. A separate journal test proves posting uses
new edited amounts, not the preserved old voucher snapshot. Xvfb tests cover both actual view
routes with legacy JDBC blocked, sorted identity, real cell editor validation and confirmation,
stale reload, input-generation changes, accepted-save selection replacement and uncertain UI
blocking. Description/discount regressions additionally cover nullable/empty text, Unicode,
0/100 percentage bounds, exact storage, duplicate/foreign detail keys, historical out-of-range
preservation, partial detail failures rolling back earlier date/amount/detail writes while
retaining outer-transaction work, replay/unknown commits and separate posting with new discounts.
Accounting regressions cover both document kinds, account-plan/rate staleness, inactive/foreign-
year/required-dimension account refusal, missing/out-of-range rate refusal, nullable historical
values, late accounting failures restoring earlier date/amount/detail writes, local savepoint
outer-work preservation, unknown commits, real cell editors and separate balanced posting to
the new sales/VAT accounts. Header-tax regressions additionally verify all-SQL preservation
excluding only four intended header columns, exact/range limits, zero/100 rates, exemption
transitions, nullable/historical rate preservation, no-op and replay, simultaneous code/rate
changes, stale header rejection, partial-write rollback/retry, outer-transaction preservation,
unknown commits before/after durability and separate balanced invoice/credit posting using
edited rates. Exchange regressions cover both kinds, all-SQL preservation excluding only
currency_rate, unchanged currency/reference data, explicit/missing/same currency contexts,
exact/positive bounds, raw historical preservation, source/base staleness versus irrelevant
catalogue defaults, unrounded-equivalent disclosure, rollback/retry, outer-work savepoints,
unknown commits, real view confirmation and separate balanced converted journals.
These bounded editors do not establish full legacy financial editor or production
format-3 GUI parity.

## Definition of done for issue #75

Issue #75 is complete only when new databases contain no domain `OTHER` columns, supported legacy databases migrate through staging with verified rollback artifacts, every supported persisted domain is represented explicitly, normal GUI/CLI runtime does not deserialize legacy domain objects, and accounting behavior plus relationships match across migration fixtures on all supported platforms.
