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
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.TaxValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineDimensions;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ManualLine;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
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
     * @param project nullable raw project identity
     * @param resultUnit nullable raw result-unit identity
     * @param productNumber nullable stored product-number snapshot
     * @param unit nullable stored unit-name snapshot
     */
    public record Line(
            int rowNumber,
            String description,
            Integer account,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            String taxCode,
            String project,
            String resultUnit,
            String productNumber,
            String unit) {
        /** Preserves callers constructing unlinked rows. */
        public Line(
                int rowNumber,
                String description,
                Integer account,
                BigDecimal quantity,
                BigDecimal unitPrice,
                BigDecimal discount,
                String taxCode,
                String project,
                String resultUnit) {
            this(
                    rowNumber,
                    description,
                    account,
                    quantity,
                    unitPrice,
                    discount,
                    taxCode,
                    project,
                    resultUnit,
                    null,
                    null);
        }
    }

    /** Immutable company-scoped dimension choice. */
    public record Dimension(String number, String name, boolean concluded) {}

    /** Selected-year account choice; no legacy account lookup is needed. */
    public record Account(int number, String description, boolean active,
            boolean projectRequired, boolean resultUnitRequired) {}

    /** Persisted document tax choice; a missing stored rate stays missing. */
    public record Tax(String code, BigDecimal rate) {}

    /**
     * Company-scoped catalogue snapshot; ambiguous, expired or compound products are not
     * selectable.
     */
    public record Product(
            long id,
            String number,
            String description,
            String unit,
            boolean stockGoods,
            boolean compound,
            boolean selectable) {
        @Override
        public String toString() {
            return number + " / " + (description == null ? "" : description);
        }
    }

    /**
     * Immutable original values; no hydrated domain objects escape.
     *
     * @param companyId company migration identity
     * @param yearId explicit accounting year identity
     * @param accounts immutable selected-year account choices
     * @param taxes immutable stored document tax rates
     * @param projects immutable company projects
     * @param resultUnits immutable company result units
     * @param products immutable company product choices with explicit eligibility
     * @param stockInfluencing stored document stock flag, never changed by this editor
     * @param taxFree effective stored document tax-exempt flag
     * @param headerTax raw stored flag and nullable rates
     * @param kind document family
     * @param id stable SQL identity
     * @param number nullable business number
     * @param customer nullable customer snapshot
     * @param currency nullable currency snapshot code
     * @param baseCurrency nullable explicit company accounting currency code
     * @param exchangeRate nullable raw stored document rate
     * @param accountingEquivalent unrounded total times stored positive rate, or null for missing
     *     context
     * @param values original dates and complete row amounts
     * @param lines stored rows in key order
     * @param total original total with explicit company rounding
     */
    public record Data(
            int companyId,
            int yearId,
            List<Account> accounts,
            List<Tax> taxes,
            List<Dimension> projects,
            List<Dimension> resultUnits,
            List<Product> products,
            boolean stockInfluencing,
            boolean taxFree,
            TaxValues headerTax,
            Kind kind,
            long id,
            Integer number,
            String customer,
            String currency,
            String baseCurrency,
            BigDecimal exchangeRate,
            BigDecimal accountingEquivalent,
            FinancialValues values,
            List<Line> lines,
            BigDecimal total) {
        public Data {
            lines = List.copyOf(lines);
            accounts = List.copyOf(accounts);
            taxes = List.copyOf(taxes);
            projects = List.copyOf(projects);
            resultUnits = List.copyOf(resultUnits);
            products = List.copyOf(products);
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
        private final BigDecimal accountingEquivalent;

        private Plan(Snapshot snapshot, FinancialValues proposed, BigDecimal total, BigDecimal accountingEquivalent) {
            this.snapshot = snapshot;
            this.proposed = proposed;
            this.total = total;
            this.accountingEquivalent = accountingEquivalent;
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

        /** @return unrounded document-total conversion, not a promised journal total */
        public BigDecimal accountingEquivalent() { return accountingEquivalent; }
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
     * @param proposed dates, complete amount row-key set and optional row/tax/exchange patches
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
        var lines = new ArrayList<>(source.data.lines());
        var removed = new java.util.HashSet<Integer>();
        var originalKeys = lines.stream().map(Line::rowNumber).collect(java.util.stream.Collectors.toSet());
        for (int key : proposed.removedRows()) {
            if (!removed.add(key)) throw new IllegalArgumentException("Duplicate removed row key");
            if (!originalKeys.contains(key))
                throw new IllegalArgumentException("Missing or foreign removed row key");
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (removed.contains(lines.get(i).rowNumber())) { lines.remove(i);source.document.getRows().remove(i); }
        }
        if (!removed.isEmpty() && lines.isEmpty() && proposed.addedRows().isEmpty())
            throw new IllegalArgumentException(
                    "Cannot remove the final row; use explicit document deletion instead");
        var headerTax = proposed.tax() == null ? source.data.headerTax() : proposed.tax();
        validateTaxChanges(source.data.headerTax(), headerTax);
        source.document.setTaxFree(headerTax.taxFree());
        source.document.setStoredTaxRates(headerTax.rate1(), headerTax.rate2(), headerTax.rate3());
        var exchangeRate = proposed.exchange() == null ? source.data.exchangeRate() : proposed.exchange().rate();
        if (!same(source.data.exchangeRate(), exchangeRate)) {
            if (exchangeRate == null || exchangeRate.signum() <= 0)
                throw new IllegalArgumentException(
                        "Changed exchange rates must be explicit and positive");
            if (!knownCode(source.data.currency()) || !knownCode(source.data.baseCurrency()))
                throw new IllegalArgumentException(
                        "Exchange editing requires explicit document and company currency codes");
            if (source.data.currency().equals(source.data.baseCurrency())
                    && exchangeRate.compareTo(BigDecimal.ONE) != 0)
                throw new IllegalArgumentException(
                        "Identical document and accounting currencies require rate 1");
        }
        source.document.setCurrencyRate(exchangeRate);
        var indexed = indexed(proposed.rows());
        if (indexed.size() != lines.size())
            throw new IllegalArgumentException(
                    "Amounts must match the complete surviving stored row-key set");
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var values = indexed.remove(line.rowNumber());
            if (values == null)
                throw new IllegalArgumentException("Missing/foreign stored row key");
            if (source.data.stockInfluencing()
                    && !same(line.quantity(), values.quantity())
                    && values.quantity() != null
                    && source.data.products().stream()
                            .anyMatch(
                                    p ->
                                            Objects.equals(p.number(), line.productNumber())
                                                    && (p.stockGoods() || p.compound()))) {
                requireStockQuantity(values.quantity());
            }
            source.document.getRows().get(i).setQuantity(values.quantity());
            source.document.getRows().get(i).setUnitprice(values.unitPrice());
        }
        if (!indexed.isEmpty()) throw new IllegalArgumentException("Foreign stored row key");
        var details = indexedDetails(proposed.details());
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
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
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var value = accounting.remove(line.rowNumber());
            if (value == null) continue;
            if (!Objects.equals(line.account(), value.account())
                    && value.account() != null
                    && (value.account() < 3000
                            || value.account() > 3999
                            || source.data.accounts().stream()
                                    .noneMatch(a -> a.number() == value.account() && a.active())))
                throw new IllegalArgumentException(
                        "Sales account must be an active selected-year account within 3000..3999");
            if (!Objects.equals(line.taxCode(), value.taxCode()) && value.taxCode() != null) {
                var rate = taxRate(headerTax, value.taxCode());
                if (!validRate(rate))
                    throw new IllegalArgumentException(
                            "Selected tax code requires a stored document rate within 0..100");
            }
            source.document.getRows().get(i).setAccountNr(value.account());
            source.document.getRows().get(i).setTaxCode(value.taxCode() == null ? null : SSTaxCode.valueOf(value.taxCode()));
        }
        if (!accounting.isEmpty())
            throw new IllegalArgumentException("Foreign stored accounting row key");
        var dimensions = indexedDimensions(proposed.dimensions());
        for (int i = 0; i < lines.size(); i++) {
            var original = lines.get(i);
            var row = source.document.getRows().get(i);
            var value = dimensions.remove(original.rowNumber());
            if (value != null) {
                if (!Objects.equals(original.project(), value.project())
                        && value.project() != null
                        && !validDimension(source.data.projects(), value.project()))
                    throw new IllegalArgumentException(
                            "Project must be an open project in the document company");
                if (!Objects.equals(original.resultUnit(), value.resultUnit())
                        && value.resultUnit() != null
                        && !validDimension(source.data.resultUnits(), value.resultUnit()))
                    throw new IllegalArgumentException(
                            "Result unit must exist in the document company");
                row.setProjectNr(value.project());row.setResultUnitNr(value.resultUnit());
            }
            if (!Objects.equals(original.account(), row.getAccountNr())
                    || !Objects.equals(original.project(), row.getProjectNr())
                    || !Objects.equals(original.resultUnit(), row.getResultUnitNr())) {
                var account = source.data.accounts().stream().filter(a -> Objects.equals(a.number(), row.getAccountNr())).findFirst();
                if (account.isPresent()
                        && ((account.get().projectRequired()
                                        && !validDimension(
                                                source.data.projects(), row.getProjectNr()))
                                || (account.get().resultUnitRequired()
                                        && !validDimension(
                                                source.data.resultUnits(), row.getResultUnitNr()))))
                    throw new IllegalArgumentException(
                            "Account requires valid company project/result-unit dimensions");
            }
        }
        if (!dimensions.isEmpty())
            throw new IllegalArgumentException("Foreign stored dimension row key");
        int maximum =
                Math.max(
                        -1,
                        source.data.lines().stream().mapToInt(Line::rowNumber).max().orElse(-1));
        var addedKeys = new java.util.HashSet<Integer>();
        for (var row : proposed.addedRows()) {
            if (row.rowNumber() <= maximum || !addedKeys.add(row.rowNumber()))
                throw new IllegalArgumentException(
                        "Added rows require distinct fresh keys above the source maximum");
            source.document.getRows().add(manualRow(source.data, headerTax, row));
        }
        if (!sameTax(source.data.headerTax(), headerTax) && !headerTax.taxFree()) {
            for (var row : source.document.getRows()) {
                if (row.getTaxCode() != null
                        && row.getTaxCode() != SSTaxCode.TAXRATE_0
                        && !validRate(taxRate(headerTax, row.getTaxCode().name())))
                    throw new IllegalArgumentException(
                            "Taxable edited headers require present stored rates within 0..100 for"
                                + " all used VAT codes");
            }
        }
        var total = se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(source.document, source.rounding);
        return new Plan(expected, proposed, total,
                equivalent(total, exchangeRate, source.data.currency(), source.data.baseCurrency()));
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
                                        return value != null && (!same(line.quantity(), value.quantity())
                                                || !same(line.unitPrice(), value.unitPrice()));
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
            var dimensionPatch = indexedDimensions(fresh.proposed.dimensions());
            var changedDimensions = original.lines().stream().filter(line -> {
                var value = dimensionPatch.get(line.rowNumber());
                return value != null && (!Objects.equals(line.project(), value.project()) || !Objects.equals(line.resultUnit(), value.resultUnit()));
            }).map(line -> dimensionPatch.get(line.rowNumber())).toList();
            var values =
                    new FinancialValues(
                            fresh.proposed.date(),
                            fresh.proposed.dueDate(),
                            changed,
                            changedDetails,
                            changedAccounting,
                            fresh.proposed.tax() != null
                                            && !sameTax(original.headerTax(), fresh.proposed.tax())
                                    ? fresh.proposed.tax()
                                    : null,
                            fresh.proposed.exchange() != null
                                            && !same(
                                                    original.exchangeRate(),
                                                    fresh.proposed.exchange().rate())
                                    ? fresh.proposed.exchange()
                                    : null,
                            changedDimensions,
                            fresh.proposed.removedRows(),
                            fresh.proposed.addedRows());
            var store = new NormalizedInvoiceStore(connection);
            if (original.kind() == Kind.INVOICE)
                store.updateInvoiceFinancialValues(original.companyId(), original.id(), values);
            else store.updateCreditFinancialValues(original.companyId(), original.id(), values);
            var verified = read(original.companyId(), original.yearId(), original.kind(), original.id()).data;
            if (!Objects.equals(verified.values().date(), fresh.proposed.date())
                    || !Objects.equals(verified.values().dueDate(), fresh.proposed.dueDate())
                    || verified.total().compareTo(fresh.total) != 0)
                throw new SQLException("Stored financial values differ from preview");
            var actualKeys = verified.lines().stream().map(Line::rowNumber).collect(java.util.stream.Collectors.toSet());
            var expectedKeys = new java.util.HashSet<>(proposed.keySet());
            var added = new HashMap<Integer, ManualLine>();
            for (var row : fresh.proposed.addedRows()) {
                expectedKeys.add(row.rowNumber());
                added.put(row.rowNumber(), row);
            }
            if (!actualKeys.equals(expectedKeys))
                throw new SQLException("Stored row keys differ from preview");
            var expectedRate = fresh.proposed.exchange() == null ? original.exchangeRate() : fresh.proposed.exchange().rate();
            if (!same(verified.exchangeRate(), expectedRate)
                    || !same(verified.accountingEquivalent(), fresh.accountingEquivalent()))
                throw new SQLException("Stored exchange values differ from preview");
            var expectedTax = fresh.proposed.tax() == null ? original.headerTax() : fresh.proposed.tax();
            if (!sameTax(verified.headerTax(), expectedTax))
                throw new SQLException("Stored header tax values differ from preview");
            var expectedDetails = new HashMap<Integer, LineDetails>();
            for (var row : original.lines())
                expectedDetails.put(row.rowNumber(), new LineDetails(row.rowNumber(), row.description(), row.discount()));
            expectedDetails.putAll(detailPatch);
            var expectedAccounting = new HashMap<Integer, LineAccounting>();
            for (var row : original.lines())
                expectedAccounting.put(row.rowNumber(), new LineAccounting(row.rowNumber(), row.account(), row.taxCode()));
            expectedAccounting.putAll(accountingPatch);
            var expectedDimensions = new HashMap<Integer, LineDimensions>();
            for (var row : original.lines()) expectedDimensions.put(row.rowNumber(), new LineDimensions(row.rowNumber(), row.project(), row.resultUnit()));
            expectedDimensions.putAll(dimensionPatch);
            for (var row : verified.lines()) {
                var inserted = added.get(row.rowNumber());
                if (inserted != null) {
                    if (!Objects.equals(row.description(), inserted.description())
                            || !same(row.quantity(), inserted.quantity())
                            || !same(row.unitPrice(), inserted.unitPrice())
                            || !same(row.discount(), inserted.discount())
                            || !Objects.equals(row.account(), inserted.account())
                            || !Objects.equals(row.taxCode(), inserted.taxCode())
                            || !Objects.equals(row.project(), inserted.project())
                            || !Objects.equals(row.resultUnit(), inserted.resultUnit())
                            || !Objects.equals(
                                    row.productNumber(),
                                    inserted.product() == null ? null : inserted.product().number())
                            || !Objects.equals(
                                    row.unit(),
                                    inserted.product() == null ? null : inserted.product().unit()))
                        throw new SQLException("Stored new manual row differs from preview");
                    continue;
                }
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
                var dimension = expectedDimensions.get(row.rowNumber());
                if (dimension == null || !Objects.equals(row.project(), dimension.project()) || !Objects.equals(row.resultUnit(), dimension.resultUnit()))
                    throw new SQLException("Stored dimensions differ from preview");
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
        var selectedYear =
                accounting.getYears().stream()
                        .filter(y -> y.getId() == year)
                        .findFirst()
                        .orElseThrow(() -> new SQLException("Missing or foreign accounting year"));
        var accountRows = accounting.getAccounts(selectedYear);
        var registers = new NormalizedRegisterStore(connection);
        var projectRows = registers.getProjects(company);
        var unitRows = registers.getResultUnits(company);
        var projects = projectRows.stream().map(p -> new Dimension(p.getNumber(), p.getName(), p.getConcluded())).toList();
        var units = unitRows.stream().map(u -> new Dimension(u.getNumber(), u.getName(), false)).toList();
        var accounts = accountRows.stream().map(a -> new Account(a.getNumber(), a.getDescription(),
                a.isActive(), a.isProjectRequired(), a.isResultUnitRequired()))
                .sorted(java.util.Comparator.comparingInt(Account::number)).toList();
        List<Product> products = new ArrayList<>();
        try (var statement =
                connection.prepareStatement(
                        "SELECT"
                            + " p.id,p.number,p.description,p.unit_name,p.stock_goods,p.expired,(SELECT"
                            + " COUNT(*) FROM product q WHERE q.company_id=p.company_id AND"
                            + " q.number=p.number),(SELECT COUNT(*) FROM product_component r WHERE"
                            + " r.product_id=p.id) FROM product p WHERE p.company_id=(SELECT id"
                            + " FROM company WHERE legacy_id=?) ORDER BY p.id")) {
            statement.setInt(1, company);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String number = rows.getString(2);
                    products.add(
                            new Product(
                                    rows.getLong(1),
                                    number,
                                    rows.getString(3),
                                    rows.getString(4),
                                    rows.getBoolean(5),
                                    rows.getInt(8) != 0,
                                    !rows.getBoolean(6)
                                            && number != null
                                            && !number.isBlank()
                                            && rows.getInt(7) == 1
                                            && rows.getInt(8) == 0));
                }
            }
        }
        var headerTax =
                (kind == Kind.INVOICE
                                ? store.findInvoiceTaxValues(company, id)
                                : store.findCreditTaxValues(company, id))
                        .orElseThrow(() -> new SQLException("Missing tax target"));
        var rates = java.util.Arrays.asList(headerTax.rate1(), headerTax.rate2(), headerTax.rate3());
        var taxes =
                List.of(
                        new Tax("TAXRATE_0", BigDecimal.ZERO),
                        new Tax("TAXRATE_1", rates.get(0)),
                        new Tax("TAXRATE_2", rates.get(1)),
                        new Tax("TAXRATE_3", rates.get(2)));
        boolean rounding = selected.isRoundingOff();
        String baseCurrency;
        try (var statement =
                connection.prepareStatement(
                        "SELECT currency_code FROM company WHERE legacy_id=?")) {
            statement.setInt(1, company);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Missing company currency context");
                baseCurrency = row.getString(1);
            }
        }
        String table =
                kind == Kind.INVOICE ? "customer_invoice_row" : "customer_credit_invoice_row";
        String owner = kind == Kind.INVOICE ? "invoice_id" : "credit_invoice_id";
        List<Line> lines = new ArrayList<>();
        try (var statement =
                connection.prepareStatement(
                        "SELECT"
                            + " row_number,description,account_number,quantity,unit_price,discount,tax_code,project_number,result_unit_number,product_number,unit_name"
                            + " FROM "
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
                                    rows.getString(7),
                                    rows.getString(8),
                                    rows.getString(9),
                                    rows.getString(10),
                                    rows.getString(11)));
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
        var documentCurrency = document.getCurrency() == null ? null : document.getCurrency().getName();
        var total = se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(document, rounding);
        var data =
                new Data(
                        company,
                        year,
                        accounts,
                        taxes,
                        projects,
                        units,
                        products,
                        ((SSInvoice) document).isStockInfluencing(),
                        document.getTaxFree(),
                        headerTax,
                        kind,
                        id,
                        document.getNumber(),
                        document.getCustomerName(),
                        documentCurrency,
                        baseCurrency,
                        document.getCurrencyRate(),
                        equivalent(
                                total, document.getCurrencyRate(), documentCurrency, baseCurrency),
                        values,
                        lines,
                        total);
        var writer = new CanonicalFingerprintWriter();
        writer.writeInteger(company).writeInteger(year).writeBoolean(rounding).writeString(baseCurrency);
        for (var account : accountRows.stream().sorted(java.util.Comparator.comparing(a -> a.getNumber())).toList()) {
            writer.writeInteger(account.getNumber()).writeString(account.getDescription())
                    .writeString(account.getSRUCode()).writeString(account.getVATCode()).writeString(account.getReportCode())
                    .writeBoolean(account.isActive()).writeBoolean(account.isProjectRequired())
                    .writeBoolean(account.isResultUnitRequired());
        }
        for (var project : projectRows) writer.writeString(project.getNumber()).writeString(project.getName())
                .writeString(project.getDescription()).writeBoolean(project.getConcluded())
                .writeString(project.getLocalConcludedDate() == null ? null : project.getLocalConcludedDate().toString());
        writer.writeBoundary("result_units");
        for (var unit : unitRows) writer.writeString(unit.getNumber()).writeString(unit.getName()).writeString(unit.getDescription());
        writer.writeBoundary("product_selection_context");
        for (var product : products) {
            writer.writeString(Long.toString(product.id()))
                    .writeString(product.number())
                    .writeString(product.description())
                    .writeString(product.unit())
                    .writeBoolean(product.stockGoods())
                    .writeBoolean(product.compound())
                    .writeBoolean(product.selectable());
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

    private static void requireStockQuantity(BigDecimal quantity) {
        try {
            quantity.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(
                    "Stock product quantity must be an exact 32-bit integer", invalid);
        }
    }

    private static se.swedsoft.bookkeeping.data.base.SSSaleRow manualRow(
            Data data, TaxValues tax, ManualLine value) {
        if (value.description() == null || value.description().isBlank())
            throw new IllegalArgumentException("New manual rows require a description");
        if (value.quantity() == null
                || value.quantity().signum() <= 0
                || value.unitPrice() == null
                || value.unitPrice().signum() < 0)
            throw new IllegalArgumentException(
                    "New manual rows require positive quantity and non-negative price");
        if (value.discount() != null
                && (value.discount().signum() < 0
                        || value.discount().compareTo(BigDecimal.valueOf(100)) > 0))
            throw new IllegalArgumentException("New manual row discount must be within 0..100");
        var account =
                data.accounts().stream()
                        .filter(
                                a ->
                                        Objects.equals(a.number(), value.account())
                                                && a.active()
                                                && a.number() >= 3000
                                                && a.number() <= 3999)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "New manual rows require an active selected-year"
                                                    + " sales account"));
        if (value.taxCode() == null || !validRate(taxRate(tax, value.taxCode())))
            throw new IllegalArgumentException(
                    "New manual rows require an explicit VAT code with a present bounded stored"
                        + " rate");
        if ((value.project() != null && !validDimension(data.projects(), value.project()))
                || (value.resultUnit() != null
                        && !validDimension(data.resultUnits(), value.resultUnit()))
                || (account.projectRequired() && !validDimension(data.projects(), value.project()))
                || (account.resultUnitRequired()
                        && !validDimension(data.resultUnits(), value.resultUnit())))
            throw new IllegalArgumentException("New manual row requires valid company dimensions");
        if (value.product() != null) {
            var link = value.product();
            var product =
                    data.products().stream()
                            .filter(p -> p.id() == link.id())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Missing or foreign product"));
            if (!product.selectable()
                    || !Objects.equals(product.number(), link.number())
                    || !Objects.equals(product.unit(), link.unit())) {
                throw new IllegalArgumentException(
                        "Product identity/unit changed or is not selectable");
            }
            if (data.stockInfluencing() && product.stockGoods()) {
                requireStockQuantity(value.quantity());
            }
        }
        var row = new se.swedsoft.bookkeeping.data.base.SSSaleRow();
        row.setProductNr(value.product() == null ? null : value.product().number());
        row.setUnit(
                value.product() == null || value.product().unit() == null
                        ? null
                        : new se.swedsoft.bookkeeping.data.common.SSUnit(
                                value.product().unit(), null));
        row.setDescription(value.description());
        row.setQuantity(value.quantity());
        row.setUnitprice(value.unitPrice());
        row.setDiscount(value.discount());
        row.setAccountNr(value.account());
        row.setTaxCode(SSTaxCode.valueOf(value.taxCode()));
        row.setProjectNr(value.project());
        row.setResultUnitNr(value.resultUnit());
        return row;
    }

    private static boolean validDimension(List<Dimension> choices, String number) {
        return number != null && !number.isBlank() && choices.stream().anyMatch(d -> d.number().equals(number) && !d.concluded());
    }

    private static Map<Integer, LineDimensions> indexedDimensions(List<LineDimensions> rows) {
        Map<Integer, LineDimensions> map = new HashMap<>();
        for (var row : rows) if (map.put(row.rowNumber(), row) != null)
            throw new IllegalArgumentException("Duplicate stored dimension row key");
        return map;
    }

    private static boolean knownCode(String code) { return code != null && !code.isBlank(); }

    private static BigDecimal equivalent(BigDecimal total, BigDecimal rate, String currency, String base) {
        if (rate == null || rate.signum() <= 0 || !knownCode(currency) || !knownCode(base)) return null;
        return total.multiply(rate);
    }

    private static void validateTaxChanges(TaxValues before, TaxValues after) {
        for (String code : List.of("TAXRATE_1", "TAXRATE_2", "TAXRATE_3")) {
            var rate = taxRate(after, code);
            if (!same(taxRate(before, code), rate) && rate != null && !validRate(rate))
                throw new IllegalArgumentException(
                        "Changed stored VAT rates must be within 0..100");
        }
    }

    private static BigDecimal taxRate(TaxValues tax, String code) {
        return switch (code) {
            case "TAXRATE_0" -> BigDecimal.ZERO;
            case "TAXRATE_1" -> tax.rate1();
            case "TAXRATE_2" -> tax.rate2();
            case "TAXRATE_3" -> tax.rate3();
            default -> throw new IllegalArgumentException("Unknown stored tax code");
        };
    }

    private static boolean validRate(BigDecimal rate) {
        return rate != null && rate.signum() >= 0 && rate.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    private static boolean sameTax(TaxValues a, TaxValues b) {
        return a.taxFree() == b.taxFree() && same(a.rate1(), b.rate1())
                && same(a.rate2(), b.rate2()) && same(a.rate3(), b.rate3());
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
