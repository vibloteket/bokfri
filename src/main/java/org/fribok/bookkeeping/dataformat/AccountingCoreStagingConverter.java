package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSMonth;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Copies the accounting core from legacy object tables to an isolated normalized staging catalog. */
public final class AccountingCoreStagingConverter {

    /** Converts and independently fingerprints source and destination before returning. */
    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(normalized, "normalized");
        Snapshot source = readLegacy(legacy);
        SemanticFingerprint sourceFingerprint = fingerprint(source);
        try {
            writeNormalized(normalized, source);
            Snapshot destination = readNormalized(normalized);
            SemanticFingerprint destinationFingerprint = fingerprint(destination);
            List<String> differences = sourceFingerprint.differences(destinationFingerprint);
            if (!differences.isEmpty()) {
                normalized.rollback();
                throw new SQLException("Accounting-core staging fingerprint mismatch: "
                        + String.join("; ", differences));
            }
            normalized.commit();
            return new ConversionResult(sourceFingerprint, destinationFingerprint,
                    source.companies.size(), source.years.size(), source.accounts.size(),
                    source.vouchers.size(), source.rows.size(), source.gapAdjustments,
                    source.overlapResolutions);
        } catch (SQLException | RuntimeException exception) {
            normalized.rollback();
            throw exception;
        }
    }

    private static Snapshot readLegacy(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        readLegacyLookups(connection, snapshot);
        readCustomerLookups(connection, snapshot);
        readSupplierLookups(connection, snapshot);
        readProductLookups(connection, snapshot);
        readInvoiceLookups(connection, snapshot);
        readPaymentLookups(connection, snapshot);
        readSupplierInvoiceLookups(connection, snapshot);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, company FROM tbl_company ORDER BY id")) {
            while (result.next()) {
                int id = result.getInt(1);
                SSNewCompany company = (SSNewCompany) result.getObject(2);
                String currencyCode = company.getCurrency() == null
                        ? null : company.getCurrency().getName();
                snapshot.companies.add(new CompanyRow(id, company.getName(),
                        company.getCorporateID(), company.getVATNumber(), currencyCode));
                if (company.getCurrency() != null) {
                    snapshot.addLookup("currency", currencyCode,
                            company.getCurrency().getDescription(),
                            company.getCurrency().getExchangeRate());
                }
                addCompanyLookup(snapshot, "unit", company.getStandardUnit());
                addCompanyLookup(snapshot, "payment-term", company.getPaymentTerm());
                addCompanyLookup(snapshot, "delivery-term", company.getDeliveryTerm());
                addCompanyLookup(snapshot, "delivery-way", company.getDeliveryWay());
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, companyid, accountingyear FROM tbl_accountingyear ORDER BY id")) {
            while (result.next()) {
                int id = result.getInt(1);
                int companyId = result.getInt(2);
                SSNewAccountingYear year = (SSNewAccountingYear) result.getObject(3);
                SSAccountPlan plan = year.getAccountPlan();
                snapshot.years.add(new YearRow(id, companyId, year.getLocalFrom(), year.getLocalTo(),
                        plan.getId(), plan.getName(), plan.getBaseName(), plan.getAssessementYear(),
                        plan.getType() == null ? null : plan.getType().getName()));
                List<SSAccount> accounts = new ArrayList<>(plan.getAccounts());
                accounts.sort(Comparator.comparing(SSAccount::getNumber,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
                for (SSAccount account : accounts) {
                    Integer number = account.getNumber();
                    snapshot.accounts.add(new AccountRow(id, number, account.getDescription(),
                            account.getSRUCode(), account.getVATCode(), account.getReportCode(),
                            account.isActive(), account.isProjectRequired(),
                            account.isResultUnitRequired()));
                }
                year.getInBalance().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(SSAccount::getNumber)))
                        .forEach(entry -> snapshot.openingBalances.add(
                                new AmountRow(id, entry.getKey().getNumber(), null, null,
                                        entry.getValue())));
                if (year.getBudget() != null) {
                    year.getBudget().getBudget().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(
                                    Comparator.comparing(SSMonth::getLocalFrom)))
                            .forEach(month -> month.getValue().entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey(
                                            Comparator.comparing(SSAccount::getNumber)))
                                    .forEach(entry -> snapshot.budgets.add(new AmountRow(id,
                                            entry.getKey().getNumber(), month.getKey().getLocalFrom(),
                                            month.getKey().getLocalTo(), entry.getValue()))));
                }
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, yearid, number, voucher FROM tbl_voucher ORDER BY yearid, number, id")) {
            while (result.next()) {
                long legacyId = result.getLong(1);
                int yearId = result.getInt(2);
                SSVoucher voucher = (SSVoucher) result.getObject(4);
                Integer corrects = voucher.getCorrects() == null
                        ? null : voucher.getCorrects().getNumber();
                Integer correctedBy = voucher.getCorrectedBy() == null
                        ? null : voucher.getCorrectedBy().getNumber();
                snapshot.vouchers.add(new VoucherRow(legacyId, yearId, voucher.getNumber(),
                        voucher.getLocalDate(), voucher.getDescription(), corrects, correctedBy));
                int rowNumber = 0;
                for (SSVoucherRow row : voucher.getRows()) {
                    Instant editedAt = null;
                    if (row.getLocalEditedDate() != null) {
                        LegacySwedishTimeResolver.Resolution resolution =
                                LegacySwedishTimeResolver.resolve(row.getLocalEditedDate());
                        editedAt = resolution.instant();
                        if (resolution.kind()
                                == LegacySwedishTimeResolver.ResolutionKind.FORWARD_BY_GAP) {
                            snapshot.gapAdjustments++;
                        } else if (resolution.kind()
                                == LegacySwedishTimeResolver.ResolutionKind.EARLIER_OFFSET_IN_OVERLAP) {
                            snapshot.overlapResolutions++;
                        }
                    }
                    snapshot.rows.add(new VoucherLine(voucher.getNumber(), yearId, rowNumber++,
                            row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr(),
                            row.getDebet(), row.getCredit(), editedAt, row.getEditedSignature(),
                            row.isCrossed(), row.isAdded()));
                }
            }
        }
        snapshot.sort();
        return snapshot;
    }

    private static void readSupplierInvoiceLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT supplierinvoice FROM tbl_supplierinvoice")) {
            while (result.next()) {
                SSSupplierInvoice invoice = (SSSupplierInvoice) result.getObject(1);
                if (invoice.getCurrency() != null) {
                    snapshot.addLookup("currency", invoice.getCurrency().getName(),
                            invoice.getCurrency().getDescription(),
                            invoice.getCurrency().getExchangeRate());
                }
                addCompanyLookup(snapshot, "payment-term", invoice.getPaymentTerm());
                for (SSSupplierInvoiceRow row : invoice.getRows()) {
                    addCompanyLookup(snapshot, "unit", row.getUnit());
                }
            }
        }
    }

    private static void readPaymentLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT inpayment FROM tbl_inpayment")) {
            while (result.next()) {
                se.swedsoft.bookkeeping.data.SSInpayment payment =
                        (se.swedsoft.bookkeeping.data.SSInpayment) result.getObject(1);
                for (SSInpaymentRow row : payment.getRows()) {
                    if (row.getInvoiceCurrency() != null) {
                        snapshot.addLookup("currency", row.getInvoiceCurrency().getName(),
                                row.getInvoiceCurrency().getDescription(),
                                row.getInvoiceCurrency().getExchangeRate());
                    }
                }
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT outpayment FROM tbl_outpayment")) {
            while (result.next()) {
                se.swedsoft.bookkeeping.data.SSOutpayment payment =
                        (se.swedsoft.bookkeeping.data.SSOutpayment) result.getObject(1);
                for (SSOutpaymentRow row : payment.getRows()) {
                    if (row.getInvoiceCurrency() != null) {
                        snapshot.addLookup("currency", row.getInvoiceCurrency().getName(),
                                row.getInvoiceCurrency().getDescription(),
                                row.getInvoiceCurrency().getExchangeRate());
                    }
                }
            }
        }
    }

    private static void readInvoiceLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT invoice FROM tbl_invoice")) {
            while (result.next()) {
                SSInvoice invoice = (SSInvoice) result.getObject(1);
                SSCurrency currency = invoice.getCurrency();
                if (currency != null) {
                    snapshot.addLookup("currency", currency.getName(), currency.getDescription(),
                            currency.getExchangeRate());
                }
                addCompanyLookup(snapshot, "payment-term", invoice.getPaymentTerm());
                addCompanyLookup(snapshot, "delivery-term", invoice.getDeliveryTerm());
                addCompanyLookup(snapshot, "delivery-way", invoice.getDeliveryWay());
                for (se.swedsoft.bookkeeping.data.base.SSSaleRow row : invoice.getRows()) {
                    addCompanyLookup(snapshot, "unit", row.getUnit());
                }
            }
        }
    }

    private static void readProductLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT product FROM tbl_product")) {
            while (result.next()) {
                SSProduct product = (SSProduct) result.getObject(1);
                addCompanyLookup(snapshot, "unit", product.getUnit());
            }
        }
    }

    private static void readSupplierLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT supplier FROM tbl_supplier")) {
            while (result.next()) {
                SSSupplier supplier = (SSSupplier) result.getObject(1);
                SSCurrency currency = supplier.getStoredCurrency();
                if (currency != null) {
                    snapshot.addLookup("currency", currency.getName(), currency.getDescription(),
                            currency.getExchangeRate());
                }
                addCompanyLookup(snapshot, "payment-term", supplier.getPaymentTerm());
                addCompanyLookup(snapshot, "delivery-term", supplier.getDeliveryTerm());
                addCompanyLookup(snapshot, "delivery-way", supplier.getDeliveryWay());
            }
        }
    }

    private static void readCustomerLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT customer FROM tbl_customer")) {
            while (result.next()) {
                SSCustomer customer = (SSCustomer) result.getObject(1);
                SSCurrency currency = customer.getStoredInvoiceCurrency();
                if (currency != null) {
                    snapshot.addLookup("currency", currency.getName(), currency.getDescription(),
                            currency.getExchangeRate());
                }
                addCompanyLookup(snapshot, "payment-term", customer.getPaymentTerm());
                addCompanyLookup(snapshot, "delivery-term", customer.getDeliveryTerm());
                addCompanyLookup(snapshot, "delivery-way", customer.getDeliveryWay());
            }
        }
    }

    private static void readLegacyLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        readLegacyLookupTable(connection, snapshot, "tbl_currency", "currency", "currency");
        readLegacyLookupTable(connection, snapshot, "tbl_unit", "unit", "unit");
        readLegacyLookupTable(connection, snapshot, "tbl_paymentterm", "paymentterm", "payment-term");
        readLegacyLookupTable(connection, snapshot, "tbl_deliveryterm", "deliveryterm", "delivery-term");
        readLegacyLookupTable(connection, snapshot, "tbl_deliveryway", "deliveryway", "delivery-way");
    }

    private static void readLegacyLookupTable(Connection connection, Snapshot snapshot,
                                               String table, String objectColumn, String type)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + objectColumn + " FROM "
                     + table)) {
            while (result.next()) {
                Object value = result.getObject(1);
                if (value instanceof SSCurrency currency) {
                    snapshot.addLookup(type, currency.getName(), currency.getDescription(),
                            currency.getExchangeRate());
                } else if (value instanceof SSUnit unit) {
                    snapshot.addLookup(type, unit.getName(), unit.getDescription(), null);
                } else if (value instanceof SSPaymentTerm term) {
                    snapshot.addLookup(type, term.getName(), term.getDescription(), null);
                } else if (value instanceof SSDeliveryTerm term) {
                    snapshot.addLookup(type, term.getName(), term.getDescription(), null);
                } else if (value instanceof SSDeliveryWay way) {
                    snapshot.addLookup(type, way.getName(), way.getDescription(), null);
                } else {
                    throw new SQLException("Unexpected legacy lookup object in " + table + ": "
                            + (value == null ? "null" : value.getClass().getName()));
                }
            }
        }
    }

    private static void addCompanyLookup(Snapshot snapshot, String type, Object value)
            throws SQLException {
        if (value instanceof SSUnit unit) {
            snapshot.addLookup(type, unit.getName(), unit.getDescription(), null);
        } else if (value instanceof SSPaymentTerm term) {
            snapshot.addLookup(type, term.getName(), term.getDescription(), null);
        } else if (value instanceof SSDeliveryTerm term) {
            snapshot.addLookup(type, term.getName(), term.getDescription(), null);
        } else if (value instanceof SSDeliveryWay way) {
            snapshot.addLookup(type, way.getName(), way.getDescription(), null);
        } else if (value != null) {
            throw new SQLException("Unexpected company lookup object: " + value.getClass().getName());
        }
    }

    private static void writeLookups(Connection connection, Snapshot snapshot) throws SQLException {
        for (LookupRow row : snapshot.lookups.values()) {
            String table = switch (row.type) {
                case "currency" -> "currency";
                case "unit" -> "unit_definition";
                case "payment-term" -> "payment_term";
                case "delivery-term" -> "delivery_term";
                case "delivery-way" -> "delivery_way";
                default -> throw new SQLException("Unknown lookup type " + row.type);
            };
            String sql = "currency".equals(row.type)
                    ? "INSERT INTO currency (code,description,exchange_rate) VALUES (?,?,?)"
                    : "INSERT INTO " + table + " (name,description) VALUES (?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, row.name); statement.setString(2, row.description);
                if ("currency".equals(row.type)) statement.setBigDecimal(3, row.exchangeRate);
                statement.executeUpdate();
            }
        }
    }

    private static void readNormalizedLookups(Connection connection, Snapshot snapshot)
            throws SQLException {
        query(connection, "SELECT code,description,exchange_rate FROM currency ORDER BY code",
                r -> snapshot.addLookup("currency", r.getString(1), r.getString(2),
                        r.getBigDecimal(3)));
        readNormalizedLookupTable(connection, snapshot, "unit_definition", "unit");
        readNormalizedLookupTable(connection, snapshot, "payment_term", "payment-term");
        readNormalizedLookupTable(connection, snapshot, "delivery_term", "delivery-term");
        readNormalizedLookupTable(connection, snapshot, "delivery_way", "delivery-way");
    }

    private static void readNormalizedLookupTable(Connection connection, Snapshot snapshot,
                                                   String table, String type) throws SQLException {
        query(connection, "SELECT name,description FROM " + table + " ORDER BY name",
                r -> snapshot.addLookup(type, r.getString(1), r.getString(2), null));
    }

    private static void writeNormalized(Connection connection, Snapshot snapshot) throws SQLException {
        writeLookups(connection, snapshot);
        Map<Integer, Long> companies = new HashMap<>();
        for (CompanyRow row : snapshot.companies) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO company (legacy_id,name,corporate_id,vat_number,currency_code) "
                            + "VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, row.legacyId); statement.setString(2, row.name);
                statement.setString(3, row.corporateId); statement.setString(4, row.vatNumber);
                statement.setString(5, row.currencyCode); statement.executeUpdate();
                companies.put(row.legacyId, generatedKey(statement));
            }
        }
        Map<Integer, Long> years = new HashMap<>();
        for (YearRow row : snapshot.years) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO accounting_year (legacy_id,company_id,starts_on,ends_on) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, row.legacyId); statement.setLong(2, require(companies, row.companyLegacyId));
                statement.setObject(3, row.from); statement.setObject(4, row.to); statement.executeUpdate();
                long year = generatedKey(statement); years.put(row.legacyId, year);
                try (PreparedStatement plan = connection.prepareStatement(
                        "INSERT INTO account_plan (accounting_year_id,legacy_id,name,base_name,"
                                + "assessment_year,plan_type) VALUES (?,?,?,?,?,?)")) {
                    plan.setLong(1, year); setInteger(plan, 2, row.planLegacyId);
                    plan.setString(3, row.planName); plan.setString(4, row.baseName);
                    plan.setString(5, row.assessmentYear); plan.setString(6, row.planType);
                    plan.executeUpdate();
                }
            }
        }
        Map<YearAccount, Long> accounts = new HashMap<>();
        for (AccountRow row : snapshot.accounts) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO account (accounting_year_id,number,description,sru_code,vat_code,"
                            + "report_code,active,project_required,result_unit_required) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                long year = require(years, row.yearLegacyId);
                statement.setLong(1, year); setInteger(statement, 2, row.number);
                statement.setString(3, row.description); statement.setString(4, row.sruCode);
                statement.setString(5, row.vatCode); statement.setString(6, row.reportCode);
                statement.setBoolean(7, row.active); statement.setBoolean(8, row.projectRequired);
                statement.setBoolean(9, row.resultUnitRequired); statement.executeUpdate();
                accounts.put(new YearAccount(row.yearLegacyId, row.number), generatedKey(statement));
            }
        }
        for (AmountRow row : snapshot.openingBalances) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO opening_balance VALUES (?,?,?)")) {
                statement.setLong(1, require(years, row.yearLegacyId));
                statement.setLong(2, require(accounts, new YearAccount(row.yearLegacyId, row.accountNumber)));
                statement.setBigDecimal(3, row.amount); statement.executeUpdate();
            }
        }
        for (AmountRow row : snapshot.budgets) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO budget_entry VALUES (?,?,?,?,?)")) {
                statement.setLong(1, require(years, row.yearLegacyId));
                statement.setLong(2, require(accounts, new YearAccount(row.yearLegacyId, row.accountNumber)));
                statement.setObject(3, row.periodStart); statement.setObject(4, row.periodEnd);
                statement.setBigDecimal(5, row.amount); statement.executeUpdate();
            }
        }
        Map<YearVoucher, Long> vouchers = new HashMap<>();
        for (VoucherRow row : snapshot.vouchers) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO voucher (accounting_year_id,number,voucher_date,description) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, require(years, row.yearLegacyId)); statement.setInt(2, row.number);
                statement.setObject(3, row.date); statement.setString(4, row.description);
                statement.executeUpdate(); long id = generatedKey(statement);
                vouchers.put(new YearVoucher(row.yearLegacyId, row.number), id);
            }
        }
        for (VoucherRow row : snapshot.vouchers) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE voucher SET corrects_voucher_id=?,corrected_by_voucher_id=? WHERE id=?")) {
                setLong(statement, 1, optional(vouchers, row.yearLegacyId, row.correctsNumber));
                setLong(statement, 2, optional(vouchers, row.yearLegacyId, row.correctedByNumber));
                statement.setLong(3, require(vouchers,
                        new YearVoucher(row.yearLegacyId, row.number))); statement.executeUpdate();
            }
        }
        for (VoucherLine row : snapshot.rows) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO voucher_row VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, require(vouchers,
                        new YearVoucher(row.yearLegacyId, row.voucherNumber)));
                statement.setLong(2, require(years, row.yearLegacyId)); statement.setInt(3, row.rowNumber);
                setLong(statement, 4, row.accountNumber == null ? null
                        : require(accounts, new YearAccount(row.yearLegacyId, row.accountNumber)));
                statement.setString(5, row.projectNumber); statement.setString(6, row.resultUnitNumber);
                statement.setBigDecimal(7, row.debit); statement.setBigDecimal(8, row.credit);
                statement.setObject(9, row.editedAt == null ? null
                        : OffsetDateTime.ofInstant(row.editedAt, ZoneOffset.UTC));
                statement.setString(10, row.editedSignature); statement.setBoolean(11, row.crossed);
                statement.setBoolean(12, row.added); statement.executeUpdate();
            }
        }
    }

    /** Reads and fingerprints a normalized catalog, used after a durable reopen. */
    public SemanticFingerprint fingerprintNormalized(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        return fingerprint(readNormalized(connection));
    }

    private static Snapshot readNormalized(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        readNormalizedLookups(connection, snapshot);
        query(connection, "SELECT legacy_id,name,corporate_id,vat_number,currency_code FROM company ORDER BY legacy_id",
                result -> snapshot.companies.add(new CompanyRow(result.getInt(1), result.getString(2),
                        result.getString(3), result.getString(4), result.getString(5))));
        query(connection, "SELECT y.legacy_id,c.legacy_id,y.starts_on,y.ends_on,p.legacy_id,p.name,"
                        + "p.base_name,p.assessment_year,p.plan_type FROM accounting_year y JOIN company c "
                        + "ON c.id=y.company_id JOIN account_plan p ON p.accounting_year_id=y.id ORDER BY y.legacy_id",
                result -> snapshot.years.add(new YearRow(result.getInt(1), result.getInt(2),
                        result.getObject(3, java.time.LocalDate.class), result.getObject(4, java.time.LocalDate.class),
                        nullableInteger(result, 5), result.getString(6), result.getString(7),
                        result.getString(8), result.getString(9))));
        query(connection, "SELECT y.legacy_id,a.number,a.description,a.sru_code,a.vat_code,a.report_code,"
                        + "a.active,a.project_required,a.result_unit_required FROM account a JOIN accounting_year y "
                        + "ON y.id=a.accounting_year_id ORDER BY y.legacy_id,a.number",
                r -> snapshot.accounts.add(new AccountRow(r.getInt(1), r.getInt(2), r.getString(3),
                        r.getString(4), r.getString(5), r.getString(6), r.getBoolean(7),
                        r.getBoolean(8), r.getBoolean(9))));
        queryAmounts(connection, snapshot, "opening_balance", false);
        queryAmounts(connection, snapshot, "budget_entry", true);
        query(connection, "SELECT v.id,y.legacy_id,v.number,v.voucher_date,v.description,vc.number,vb.number "
                        + "FROM voucher v JOIN accounting_year y ON y.id=v.accounting_year_id "
                        + "LEFT JOIN voucher vc ON vc.id=v.corrects_voucher_id "
                        + "LEFT JOIN voucher vb ON vb.id=v.corrected_by_voucher_id ORDER BY y.legacy_id,v.number,v.id",
                r -> snapshot.vouchers.add(new VoucherRow(r.getLong(1), r.getInt(2), r.getInt(3),
                        r.getObject(4, java.time.LocalDate.class), r.getString(5), nullableInteger(r, 6),
                        nullableInteger(r, 7))));
        query(connection, "SELECT v.number,y.legacy_id,r.row_number,a.number,r.project_number,"
                        + "r.result_unit_number,r.debit,r.credit,r.edited_at,r.edited_signature,r.crossed,r.added "
                        + "FROM voucher_row r JOIN voucher v ON v.id=r.voucher_id "
                        + "JOIN accounting_year y ON y.id=r.accounting_year_id "
                        + "LEFT JOIN account a ON a.id=r.account_id ORDER BY y.legacy_id,v.number,r.row_number",
                r -> snapshot.rows.add(new VoucherLine(r.getInt(1), r.getInt(2), r.getInt(3),
                        nullableInteger(r, 4), r.getString(5), r.getString(6), r.getBigDecimal(7),
                        r.getBigDecimal(8), r.getObject(9) == null ? null
                                : r.getObject(9, OffsetDateTime.class).toInstant(), r.getString(10),
                        r.getBoolean(11), r.getBoolean(12))));
        snapshot.sort();
        return snapshot;
    }

    private static void queryAmounts(Connection c, Snapshot s, String table, boolean budget)
            throws SQLException {
        String columns = budget ? ",x.period_start,x.period_end"
                : ",CAST(NULL AS DATE),CAST(NULL AS DATE)";
        query(c, "SELECT y.legacy_id,a.number,x.amount" + columns + " FROM " + table
                        + " x JOIN accounting_year y ON y.id=x.accounting_year_id "
                        + "JOIN account a ON a.id=x.account_id ORDER BY y.legacy_id,a.number"
                        + (budget ? ",x.period_start" : ""), r -> {
                    AmountRow row = new AmountRow(r.getInt(1), r.getInt(2),
                            r.getObject(4, java.time.LocalDate.class),
                            r.getObject(5, java.time.LocalDate.class), r.getBigDecimal(3));
                    (budget ? s.budgets : s.openingBalances).add(row);
                });
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder builder = new SemanticFingerprintBuilder();
        var lookups = builder.domain("shared-lookups");
        for (LookupRow r : s.lookups.values()) lookups.startRecord(r.type + "/" + r.name)
                .writeString(r.type).writeString(r.name).writeString(r.description)
                .writeDecimal(canonicalAmount(r.exchangeRate));
        lookups.finish();
        var companies = builder.domain("companies");
        for (CompanyRow r : s.companies) companies.startRecord(Integer.toString(r.legacyId))
                .writeInteger(r.legacyId).writeString(r.name).writeString(r.corporateId)
                .writeString(r.vatNumber).writeString(r.currencyCode);
        companies.finish();
        var years = builder.domain("years");
        for (YearRow r : s.years) years.startRecord(Integer.toString(r.legacyId))
                .writeInteger(r.legacyId).writeInteger(r.companyLegacyId).writeDate(r.from).writeDate(r.to)
                .writeInteger(r.planLegacyId).writeString(r.planName).writeString(r.baseName)
                .writeString(r.assessmentYear).writeString(r.planType);
        years.finish();
        fingerprintAccounts(builder, s);
        fingerprintAmounts(builder, "opening-balances", s.openingBalances);
        fingerprintAmounts(builder, "budgets", s.budgets);
        var vouchers = builder.domain("vouchers");
        for (VoucherRow r : s.vouchers) vouchers.startRecord(r.yearLegacyId + "/" + r.number)
                .writeInteger(r.yearLegacyId).writeInteger(r.number).writeDate(r.date)
                .writeString(r.description).writeInteger(r.correctsNumber).writeInteger(r.correctedByNumber);
        vouchers.finish();
        var rows = builder.domain("voucher-rows");
        for (VoucherLine r : s.rows) rows.startRecord(r.yearLegacyId + "/" + r.voucherNumber + "/" + r.rowNumber)
                .writeInteger(r.yearLegacyId).writeInteger(r.rowNumber).writeInteger(r.accountNumber)
                .writeString(r.projectNumber).writeString(r.resultUnitNumber)
                .writeDecimal(canonicalAmount(r.debit)).writeDecimal(canonicalAmount(r.credit))
                .writeInstant(r.editedAt).writeString(r.editedSignature)
                .writeBoolean(r.crossed).writeBoolean(r.added)
                .addDebit(canonicalAmount(r.debit)).addCredit(canonicalAmount(r.credit));
        rows.finish();
        return builder.finish();
    }

    private static void fingerprintAccounts(SemanticFingerprintBuilder b, Snapshot s) {
        var domain = b.domain("accounts");
        for (AccountRow r : s.accounts) domain.startRecord(r.yearLegacyId + "/" + r.number)
                .writeInteger(r.yearLegacyId).writeInteger(r.number).writeString(r.description)
                .writeString(r.sruCode).writeString(r.vatCode).writeString(r.reportCode)
                .writeBoolean(r.active).writeBoolean(r.projectRequired).writeBoolean(r.resultUnitRequired);
        domain.finish();
    }

    private static void fingerprintAmounts(SemanticFingerprintBuilder b, String name, List<AmountRow> values) {
        var domain = b.domain(name);
        for (AmountRow r : values) domain.startRecord(r.yearLegacyId + "/" + r.accountNumber + "/" + r.periodStart)
                .writeInteger(r.yearLegacyId).writeInteger(r.accountNumber).writeDate(r.periodStart)
                .writeDate(r.periodEnd).writeDecimal(canonicalAmount(r.amount));
        domain.finish();
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static BigDecimal canonicalAmount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static void query(Connection c, String sql, SqlRow consumer) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) consumer.accept(result);
        }
    }
    private static long generatedKey(PreparedStatement s) throws SQLException { try (ResultSet r=s.getGeneratedKeys()) { if(!r.next()) throw new SQLException("Missing generated key"); return r.getLong(1); } }
    private static <K> long require(Map<K,Long> map,K key) throws SQLException { Long value=map.get(key); if(value==null) throw new SQLException("Missing normalized mapping for "+key); return value; }
    private static Long optional(Map<YearVoucher,Long> map,int year,Integer number) throws SQLException { return number==null?null:require(map,new YearVoucher(year,number)); }
    private static void setLong(PreparedStatement s,int i,Long v)throws SQLException{if(v==null)s.setNull(i,java.sql.Types.BIGINT);else s.setLong(i,v);}
    private static void setInteger(PreparedStatement s,int i,Integer v)throws SQLException{if(v==null)s.setNull(i,java.sql.Types.INTEGER);else s.setInt(i,v);}
    private static Integer nullableInteger(ResultSet r,int i)throws SQLException{int value=r.getInt(i);return r.wasNull()?null:value;}

    public record ConversionResult(SemanticFingerprint sourceFingerprint,
            SemanticFingerprint destinationFingerprint, long companies, long years, long accounts,
            long vouchers, long voucherRows, long daylightSavingGapAdjustments,
            long daylightSavingOverlapResolutions) {}
    @FunctionalInterface private interface SqlRow { void accept(ResultSet result) throws SQLException; }
    private record LookupKey(String type,String name) implements Comparable<LookupKey> {
        @Override public int compareTo(LookupKey other) {
            int typeOrder=type.compareTo(other.type); return typeOrder!=0?typeOrder:name.compareTo(other.name);
        }
    }
    private record LookupRow(String type,String name,String description,BigDecimal exchangeRate){}
    private record CompanyRow(int legacyId,String name,String corporateId,String vatNumber,String currencyCode){}
    private record YearRow(int legacyId,int companyLegacyId,java.time.LocalDate from,java.time.LocalDate to,Integer planLegacyId,String planName,String baseName,String assessmentYear,String planType){}
    private record AccountRow(int yearLegacyId,Integer number,String description,String sruCode,String vatCode,String reportCode,boolean active,boolean projectRequired,boolean resultUnitRequired){}
    private record AmountRow(int yearLegacyId,Integer accountNumber,java.time.LocalDate periodStart,java.time.LocalDate periodEnd,BigDecimal amount){}
    private record VoucherRow(long legacyId,int yearLegacyId,int number,java.time.LocalDate date,String description,Integer correctsNumber,Integer correctedByNumber){}
    private record VoucherLine(int voucherNumber,int yearLegacyId,int rowNumber,Integer accountNumber,String projectNumber,String resultUnitNumber,BigDecimal debit,BigDecimal credit,Instant editedAt,String editedSignature,boolean crossed,boolean added){}
    private record YearAccount(int yearLegacyId,Integer accountNumber){}
    private record YearVoucher(int yearLegacyId,int voucherNumber){}

    private static final class Snapshot {
        final Map<LookupKey,LookupRow> lookups=new java.util.TreeMap<>();
        final List<CompanyRow> companies=new ArrayList<>(); final List<YearRow> years=new ArrayList<>();
        final List<AccountRow> accounts=new ArrayList<>(); final List<AmountRow> openingBalances=new ArrayList<>();
        final List<AmountRow> budgets=new ArrayList<>(); final List<VoucherRow> vouchers=new ArrayList<>();
        final List<VoucherLine> rows=new ArrayList<>(); long gapAdjustments; long overlapResolutions;
        void addLookup(String type,String name,String description,BigDecimal exchangeRate) throws SQLException {
            if(name==null) return;
            LookupKey key=new LookupKey(type,name); LookupRow value=new LookupRow(type,name,description,exchangeRate);
            LookupRow previous=lookups.putIfAbsent(key,value);
            if(previous!=null && (!Objects.equals(previous.description,description)
                    || !sameDecimal(previous.exchangeRate,exchangeRate)))
                throw new SQLException("Conflicting legacy lookup value for "+type+" "+name);
        }
        void sort(){
            companies.sort(Comparator.comparingInt(CompanyRow::legacyId)); years.sort(Comparator.comparingInt(YearRow::legacyId));
            accounts.sort(Comparator.comparingInt(AccountRow::yearLegacyId).thenComparing(AccountRow::number,Comparator.nullsFirst(Integer::compareTo)));
            Comparator<AmountRow> amounts=Comparator.comparingInt(AmountRow::yearLegacyId).thenComparing(AmountRow::accountNumber,Comparator.nullsFirst(Integer::compareTo)).thenComparing(AmountRow::periodStart,Comparator.nullsFirst(java.time.LocalDate::compareTo));
            openingBalances.sort(amounts); budgets.sort(amounts);
            vouchers.sort(Comparator.comparingInt(VoucherRow::yearLegacyId).thenComparingInt(VoucherRow::number).thenComparingLong(VoucherRow::legacyId));
            rows.sort(Comparator.comparingInt(VoucherLine::yearLegacyId).thenComparingInt(VoucherLine::voucherNumber).thenComparingInt(VoucherLine::rowNumber));
        }
    }
}
