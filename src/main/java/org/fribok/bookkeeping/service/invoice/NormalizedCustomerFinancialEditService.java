package org.fribok.bookkeeping.service.invoice;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineDetails;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAccounting;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.SSInvoice;

/** Bounded unbooked editing; caller owns transaction, commit and exclusive application access. */
public final class NormalizedCustomerFinancialEditService {
    /** Document family; never inferred from a displayed business number. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /**
     * Immutable stored-row values; nullable amounts preserve SQL NULL.
     *
     * @param rowNumber persisted row key
     * @param description unchanged description
     * @param account unchanged nullable account number
     * @param quantity nullable exact quantity
     * @param unitPrice nullable exact unit price
     * @param discount nullable stored discount percentage
     * @param taxCode nullable persisted tax-code name
     */
    public record Line(
            int rowNumber,
            String description,
            Integer account,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            String taxCode) {}

    /** Selected-year account choice; no legacy account lookup is needed. */
    public record Account(int number, String description, boolean active,
            boolean projectRequired, boolean resultUnitRequired) {}

    /** Persisted document tax choice; a missing stored rate stays missing. */
    public record Tax(String code, BigDecimal rate) {}

    /**
     * Immutable original values; no hydrated domain objects escape.
     *
     * @param companyId company migration identity
     * @param yearId explicit accounting year identity
     * @param accounts immutable selected-year account choices
     * @param taxes immutable stored document tax rates
     * @param taxFree stored document tax-exempt flag
     * @param kind document family
     * @param id stable SQL identity
     * @param number nullable business number
     * @param customer nullable customer snapshot
     * @param currency nullable currency snapshot code
     * @param values original dates and complete row amounts
     * @param lines stored rows in key order
     * @param total original total with explicit company rounding
     */
    public record Data(
            int companyId,
            int yearId,
            List<Account> accounts,
            List<Tax> taxes,
            boolean taxFree,
            Kind kind,
            long id,
            Integer number,
            String customer,
            String currency,
            FinancialValues values,
            List<Line> lines,
            BigDecimal total) {
        public Data {
            lines = List.copyOf(lines);
            accounts = List.copyOf(accounts);
            taxes = List.copyOf(taxes);
        }
    }

    /** Opaque source token with immutable presentation values. */
    public static final class Snapshot {
        private final Data data;
        private final String signature;

        private Snapshot(Data data, String signature) {
            this.data = data;
            this.signature = signature;
        }

        /**
         * @return immutable original values
         */
        public Data data() {
            return data;
        }
    }

    /** Opaque preview binding a source token to exact proposed values and totals. */
    public static final class Plan {
        private final Snapshot snapshot;
        private final FinancialValues proposed;
        private final BigDecimal total;

        private Plan(Snapshot snapshot, FinancialValues proposed, BigDecimal total) {
            this.snapshot = snapshot;
            this.proposed = proposed;
            this.total = total;
        }

        /**
         * @return immutable original values
         */
        public Data original() {
            return snapshot.data;
        }

        /**
         * @return complete immutable proposed dates and row amounts
         */
        public FinancialValues proposed() {
            return proposed;
        }

        /**
         * @return total computed from proposed values and explicit company rounding
         */
        public BigDecimal total() {
            return total;
        }
    }

    /**
     * Verified write result; the caller still owns the outer commit.
     *
     * @param companyId company migration identity
     * @param kind document family
     * @param id stable SQL identity
     * @param total verified stored total
     */
    public record Saved(int companyId, Kind kind, long id, BigDecimal total) {}

    private record Source(SSInvoice document, Data data, boolean rounding, String signature) {}

    private final Connection connection;
    private final java.time.Clock clock;

    /**
     * Uses only the supplied context; never opens legacy storage.
     *
     * @param connection caller-owned connection
     * @param clock explicit accounting-store clock
     */
    public NormalizedCustomerFinancialEditService(Connection connection, java.time.Clock clock) {
        this.connection = Objects.requireNonNull(connection);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Reads an unbooked document without writes.
     *
     * @param company company migration identity
     * @param year explicit accounting year belonging to the company
     * @param kind document family
     * @param id stable SQL identity
     * @return opaque original snapshot
     * @throws SQLException for a missing, foreign, booked or unsupported document
     */
    public Snapshot load(int company, int year, Kind kind, long id) throws SQLException {
        var source = read(company, year, kind, id);
        return new Snapshot(source.data, source.signature);
    }

    /**
     * Revalidates the source and calculates a complete proposed patch without writes.
     *
     * @param expected original snapshot
     * @param proposed dates, complete amount row-key set and optional existing-row detail/accounting patches
     * @return opaque totals preview
     * @throws SQLException for storage errors or unsupported document projection
     * @throws IllegalArgumentException for stale or invalid inputs
     */
    public Plan plan(Snapshot expected, FinancialValues proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "proposed");
        var before = expected.data;
        var source = read(before.companyId(), before.yearId(), before.kind(), before.id());
        if (!expected.signature.equals(source.signature))
            throw new IllegalArgumentException("Financial source is stale; reload before editing");
        var indexed = indexed(proposed.rows());
        if (indexed.size() != source.data.lines().size())
            throw new IllegalArgumentException("Row insertion/removal is not supported");
        for (int i = 0; i < source.data.lines().size(); i++) {
            var line = source.data.lines().get(i);
            var values = indexed.remove(line.rowNumber());
            if (values == null)
                throw new IllegalArgumentException("Missing/foreign stored row key");
            source.document.getRows().get(i).setQuantity(values.quantity());
            source.document.getRows().get(i).setUnitprice(values.unitPrice());
        }
        if (!indexed.isEmpty()) throw new IllegalArgumentException("Foreign stored row key");
        var details = indexedDetails(proposed.details());
        for (int i = 0; i < source.data.lines().size(); i++) {
            var line = source.data.lines().get(i);
            var value = details.remove(line.rowNumber());
            if (value == null) continue;
            if (!same(line.discount(), value.discount()) && value.discount() != null
                    && (value.discount().signum() < 0 || value.discount().compareTo(BigDecimal.valueOf(100)) > 0))
                throw new IllegalArgumentException("Discount must be within 0..100");
            source.document.getRows().get(i).setDescription(value.description());
            source.document.getRows().get(i).setDiscount(value.discount());
        }
        if (!details.isEmpty()) throw new IllegalArgumentException("Foreign stored detail row key");
        var accounting = indexedAccounting(proposed.accounting());
        for (int i = 0; i < source.data.lines().size(); i++) {
            var line = source.data.lines().get(i);
            var value = accounting.remove(line.rowNumber());
            if (value == null) continue;
            if (!Objects.equals(line.account(), value.account()) && value.account() != null
                    && (value.account() < 3000 || value.account() > 3999
                            || source.data.accounts().stream().noneMatch(a -> a.number() == value.account()
                                    && a.active() && !a.projectRequired() && !a.resultUnitRequired())))
                throw new IllegalArgumentException("Sales account must be an active selected-year account within 3000..3999 without required dimensions");
            if (!Objects.equals(line.taxCode(), value.taxCode()) && value.taxCode() != null) {
                var tax = source.data.taxes().stream().filter(t -> t.code().equals(value.taxCode())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown stored tax code"));
                if (tax.rate() == null || tax.rate().signum() < 0 || tax.rate().compareTo(BigDecimal.valueOf(100)) > 0)
                    throw new IllegalArgumentException("Selected tax code requires a stored document rate within 0..100");
            }
            source.document.getRows().get(i).setAccountNr(value.account());
            source.document.getRows().get(i).setTaxCode(value.taxCode() == null ? null : SSTaxCode.valueOf(value.taxCode()));
        }
        if (!accounting.isEmpty()) throw new IllegalArgumentException("Foreign stored accounting row key");
        return new Plan(
                expected,
                proposed,
                se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(
                        source.document, source.rounding));
    }

    /**
     * Revalidates, narrowly updates and verifies a preview under a savepoint; never commits.
     *
     * @param expected confirmed immutable preview
     * @return verified write identity and total
     * @throws SQLException for persistence, book-state or transaction errors
     * @throws IllegalArgumentException if the source or preview became stale
     */
    public Saved save(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit())
            throw new SQLException("Financial edits require a caller-owned transaction");
        Savepoint point = connection.setSavepoint();
        try {
            var fresh = plan(expected.snapshot, expected.proposed);
            var original = fresh.original();
            Map<Integer, LineAmounts> proposed = indexed(fresh.proposed.rows());
            List<LineAmounts> changed =
                    original.lines().stream()
                            .filter(
                                    line -> {
                                        var value = proposed.get(line.rowNumber());
                                        return !same(line.quantity(), value.quantity())
                                                || !same(line.unitPrice(), value.unitPrice());
                                    })
                            .map(line -> proposed.get(line.rowNumber()))
                            .toList();
            var detailPatch = indexedDetails(fresh.proposed.details());
            List<LineDetails> changedDetails = original.lines().stream()
                    .filter(line -> {
                        var value = detailPatch.get(line.rowNumber());
                        return value != null && (!Objects.equals(line.description(), value.description())
                                || !same(line.discount(), value.discount()));
                    }).map(line -> detailPatch.get(line.rowNumber())).toList();
            var accountingPatch = indexedAccounting(fresh.proposed.accounting());
            List<LineAccounting> changedAccounting = original.lines().stream()
                    .filter(line -> {
                        var value = accountingPatch.get(line.rowNumber());
                        return value != null && (!Objects.equals(line.account(), value.account())
                                || !Objects.equals(line.taxCode(), value.taxCode()));
                    }).map(line -> accountingPatch.get(line.rowNumber())).toList();
            var values = new FinancialValues(
                    fresh.proposed.date(), fresh.proposed.dueDate(), changed, changedDetails, changedAccounting);
            var store = new NormalizedInvoiceStore(connection);
            if (original.kind() == Kind.INVOICE)
                store.updateInvoiceFinancialValues(original.companyId(), original.id(), values);
            else store.updateCreditFinancialValues(original.companyId(), original.id(), values);
            var verified = read(original.companyId(), original.yearId(), original.kind(), original.id()).data;
            if (!Objects.equals(verified.values().date(), fresh.proposed.date())
                    || !Objects.equals(verified.values().dueDate(), fresh.proposed.dueDate())
                    || verified.total().compareTo(fresh.total) != 0)
                throw new SQLException("Stored financial values differ from preview");
            var expectedDetails = new HashMap<Integer, LineDetails>();
            for (var row : original.lines())
                expectedDetails.put(row.rowNumber(), new LineDetails(row.rowNumber(), row.description(), row.discount()));
            expectedDetails.putAll(detailPatch);
            var expectedAccounting = new HashMap<Integer, LineAccounting>();
            for (var row : original.lines())
                expectedAccounting.put(row.rowNumber(), new LineAccounting(row.rowNumber(), row.account(), row.taxCode()));
            expectedAccounting.putAll(accountingPatch);
            for (var row : verified.lines()) {
                var wanted = proposed.get(row.rowNumber());
                if (wanted == null
                        || !same(row.quantity(), wanted.quantity())
                        || !same(row.unitPrice(), wanted.unitPrice()))
                    throw new SQLException("Stored line values differ from preview");
                var detail = expectedDetails.get(row.rowNumber());
                if (detail == null || !Objects.equals(row.description(), detail.description())
                        || !same(row.discount(), detail.discount()))
                    throw new SQLException("Stored line details differ from preview");
                var entry = expectedAccounting.get(row.rowNumber());
                if (entry == null || !Objects.equals(row.account(), entry.account())
                        || !Objects.equals(row.taxCode(), entry.taxCode()))
                    throw new SQLException("Stored line accounting differs from preview");
            }
            connection.releaseSavepoint(point);
            return new Saved(
                    original.companyId(), original.kind(), original.id(), verified.total());
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(point);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    private Source read(int company, int year, Kind kind, long id) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        var store = new NormalizedInvoiceStore(connection);
        SSInvoice document =
                kind == Kind.INVOICE
                        ? store.findInvoice(company, id)
                                .orElseThrow(() -> new SQLException("Missing or foreign invoice"))
                                .document()
                        : store.findCreditInvoice(company, id)
                                .orElseThrow(() -> new SQLException("Missing or foreign credit"))
                                .document();
        if (document.isEntered())
            throw new SQLException("Booked documents cannot be financially edited");
        var accounting = new NormalizedAccountingStore(connection, clock);
        var selected = accounting.getCompanies().stream().filter(c -> c.getId() == company)
                .findFirst().orElseThrow(() -> new SQLException("Missing company"));
        accounting.setCurrentCompany(selected);
        var selectedYear = accounting.getYears().stream().filter(y -> y.getId() == year)
                .findFirst().orElseThrow(() -> new SQLException("Missing or foreign accounting year"));
        var accountRows = accounting.getAccounts(selectedYear);
        var accounts = accountRows.stream().map(a -> new Account(a.getNumber(), a.getDescription(),
                a.isActive(), a.isProjectRequired(), a.isResultUnitRequired()))
                .sorted(java.util.Comparator.comparingInt(Account::number)).toList();
        var rates = document.getStoredTaxRates();
        var taxes = List.of(new Tax("TAXRATE_0", BigDecimal.ZERO), new Tax("TAXRATE_1", rates.get(0)),
                new Tax("TAXRATE_2", rates.get(1)), new Tax("TAXRATE_3", rates.get(2)));
        boolean rounding = selected.isRoundingOff();
        String table =
                kind == Kind.INVOICE ? "customer_invoice_row" : "customer_credit_invoice_row";
        String owner = kind == Kind.INVOICE ? "invoice_id" : "credit_invoice_id";
        List<Line> lines = new ArrayList<>();
        try (var statement =
                connection.prepareStatement(
                        "SELECT row_number,description,account_number,quantity,unit_price,discount,tax_code FROM "
                                + table
                                + " WHERE "
                                + owner
                                + "=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)"
                                + " ORDER BY row_number")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    lines.add(
                            new Line(
                                    rows.getInt(1),
                                    rows.getString(2),
                                    rows.getObject(3, Integer.class),
                                    rows.getBigDecimal(4),
                                    rows.getBigDecimal(5),
                                    rows.getBigDecimal(6),
                                    rows.getString(7)));
            }
        }
        if (lines.size() != document.getRows().size())
            throw new SQLException("Unsupported row projection");
        var values =
                new FinancialValues(
                        document.getLocalDate(),
                        document.getLocalDueDate(),
                        lines.stream()
                                .map(
                                        line ->
                                                new LineAmounts(
                                                        line.rowNumber(),
                                                        line.quantity(),
                                                        line.unitPrice()))
                                .toList());
        var data =
                new Data(
                        company,
                        year,
                        accounts,
                        taxes,
                        document.getTaxFree(),
                        kind,
                        id,
                        document.getNumber(),
                        document.getCustomerName(),
                        document.getCurrency() == null ? null : document.getCurrency().getName(),
                        values,
                        lines,
                        se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(
                                document, rounding));
        var writer = new CanonicalFingerprintWriter();
        writer.writeInteger(company).writeInteger(year).writeBoolean(rounding);
        for (var account : accountRows.stream().sorted(java.util.Comparator.comparing(a -> a.getNumber())).toList()) {
            writer.writeInteger(account.getNumber()).writeString(account.getDescription())
                    .writeString(account.getSRUCode()).writeString(account.getVATCode()).writeString(account.getReportCode())
                    .writeBoolean(account.isActive()).writeBoolean(account.isProjectRequired())
                    .writeBoolean(account.isResultUnitRequired());
        }
        CustomerDocumentFingerprint.write(writer, connection, kind == Kind.CREDIT, id);
        return new Source(document, data, rounding, writer.finish());
    }

    private static Map<Integer, LineAmounts> indexed(List<LineAmounts> rows) {
        Map<Integer, LineAmounts> map = new HashMap<>();
        for (var row : rows)
            if (map.put(row.rowNumber(), row) != null)
                throw new IllegalArgumentException("Duplicate stored row key");
        return map;
    }

    private static Map<Integer, LineDetails> indexedDetails(List<LineDetails> rows) {
        Map<Integer, LineDetails> map = new HashMap<>();
        for (var row : rows)
            if (map.put(row.rowNumber(), row) != null)
                throw new IllegalArgumentException("Duplicate stored detail row key");
        return map;
    }

    private static Map<Integer, LineAccounting> indexedAccounting(List<LineAccounting> rows) {
        Map<Integer, LineAccounting> map = new HashMap<>();
        for (var row : rows)
            if (map.put(row.rowNumber(), row) != null)
                throw new IllegalArgumentException("Duplicate stored accounting row key");
        return map;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
