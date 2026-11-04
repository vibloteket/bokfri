# Normalized persistence design

Status: design proposal for issue #75. This document does not change the active database format.

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
commit journals. Invoice/credit/supplier journal PDFs remain separate follow-ups.

Other unported PDF commands remain unsupported on normalized storage. This slice
does not activate normalized storage in the GUI or retire legacy persistence;
issue #75 remains open.

## Definition of done for issue #75

Issue #75 is complete only when new databases contain no domain `OTHER` columns, supported legacy databases migrate through staging with verified rollback artifacts, every supported persisted domain is represented explicitly, normal GUI/CLI runtime does not deserialize legacy domain objects, and accounting behavior plus relationships match across migration fixtures on all supported platforms.
