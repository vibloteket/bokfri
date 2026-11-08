package se.swedsoft.bookkeeping.data.system;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitVoucherContextTest {
    @Test
    void explicitHeadersNeverReadLegacyNumberDateOrStorageAndAllowHistoricalNullDates() throws Exception {
        try (LegacyState state = new LegacyState()) {
            state.poison();
            SSVoucher dated = new SSVoucher(17, LocalDate.of(2001, 2, 3));
            assertThat(dated.getNumber()).isEqualTo(17);
            assertThat(dated.getLocalDate()).isEqualTo(LocalDate.of(2001, 2, 3));
            assertThat(dated.getRows()).isEmpty();
            SSVoucher missingDate = new SSVoucher(-7, null);
            assertThat(missingDate.getNumber()).isEqualTo(-7);
            assertThat(missingDate.getLocalDate()).isNull();
        }
    }

    @Test
    void explicitCompressionMatchesLegacyGroupingHeadersMetadataAndUnresolvedReferences() throws Exception {
        try (LegacyState state = new LegacyState()) {
            Fixture fixture = fixture();
            state.context(fixture);
            SSVoucher source = new SSVoucher(42, null);
            source.setDescription("Compression");
            source.setCorrects(new SSVoucher(1, LocalDate.of(2020, 1, 1)));
            source.setCorrectedBy(new SSVoucher(43, null));
            SSVoucherRow first = row(1930, "P1", "U1", "10.00", null);
            first.setLocalEditedDate(LocalDateTime.of(2020, 2, 3, 4, 5));
            first.setEditedSignature("initial");
            first.setAdded(true);
            first.setCrossed(true);
            source.addVoucherRow(first);
            source.addVoucherRow(row(1930, "P1", "U1", null, "3.0"));
            source.addVoucherRow(row(1930, "P2", "U1", "0.00", null));
            source.addVoucherRow(row(9999, "missing-one", "missing-one", null, "2"));
            source.addVoucherRow(row(8888, "missing-two", "missing-two", null, "3"));
            source.addVoucherRow(row(1930, "P1", "U1", "-2", null));
            var original = snapshot(source);
            SSVoucher expected = SSVoucherMath.compress(source);
            state.poison();
            SSVoucher actual = SSVoucherMath.compress(source, fixture.plan().getAccounts(),
                    fixture.projects(), fixture.units());
            assertThat(snapshot(actual)).isEqualTo(snapshot(expected));
            assertThat(snapshot(source)).isEqualTo(original);
            assertThat(actual.getRows()).hasSize(3);
            assertThat(actual.getRows().get(0).getDebet()).isEqualByComparingTo("5");
            assertThat(actual.getRows().get(2).getCredit()).isEqualByComparingTo("5");
            assertThat(actual.getRows().get(0)).isNotSameAs(first);
            assertThat(actual.getCorrects()).isSameAs(source.getCorrects());
            assertThat(actual.getCorrectedBy()).isSameAs(source.getCorrectedBy());
        }
    }

    @Test
    void explicitCorrectionTotalsIgnoreUnknownAndCrossedRowsWithoutResolvingLegacyAccounts() throws Exception {
        try (LegacyState state = new LegacyState()) {
            SSVoucher voucher = new SSVoucher(0, null);
            voucher.addVoucherRow(row(1930, null, null, "10.25", null));
            voucher.addVoucherRow(row(1930, null, null, null, "3.10"));
            voucher.addVoucherRow(row(9999, null, null, "100", null));
            var crossed = row(1930, null, null, "900", null);
            crossed.setCrossed(true);
            voucher.addVoucherRow(crossed);
            state.poison();
            assertThat(SSVoucherMath.getCreditMinusDebetSum(voucher, List.of(new SSAccount(1930))))
                    .isEqualByComparingTo("-7.15");
        }
    }

    @Test
    void allSixExplicitBookingCalculationsMatchLegacyWithLegacyStoragePoisoned() throws Exception {
        try (LegacyState state = new LegacyState()) {
            Fixture fixture = fixture();
            SSInvoice invoice = sale(new SSInvoice(), fixture, 1);
            SSCreditInvoice credit = sale(new SSCreditInvoice(), fixture, 2);
            SSSupplierInvoice supplier = supplier(new SSSupplierInvoice(), fixture, 3);
            SSSupplierCreditInvoice supplierCredit = supplier(new SSSupplierCreditInvoice(), fixture, 4);
            SSInpayment inpayment = new SSInpayment();
            inpayment.setNumber(5);
            inpayment.setDefaultAccounts(fixture.defaults());
            SSInpaymentRow incoming = new SSInpaymentRow();
            incoming.setInvoiceNr(1);
            incoming.setInvoiceCurrency(new SSCurrency("SEK", "SEK"));
            incoming.setInvoiceCurrencyRate(BigDecimal.ONE);
            incoming.setCurrencyRate(BigDecimal.ONE);
            incoming.setValue(new BigDecimal("125"));
            inpayment.setRows(List.of(incoming));
            inpayment.setDifference(correction());
            SSOutpayment outpayment = new SSOutpayment();
            outpayment.setNumber(6);
            outpayment.setDefaultAccounts(fixture.defaults());
            SSOutpaymentRow outgoing = new SSOutpaymentRow();
            outgoing.setInvoiceNr(3);
            outgoing.setInvoiceCurrency(new SSCurrency("SEK", "SEK"));
            outgoing.setInvoiceCurrencyRate(BigDecimal.ONE);
            outgoing.setCurrencyRate(BigDecimal.ONE);
            outgoing.setValue(new BigDecimal("125"));
            outpayment.setRows(List.of(outgoing));
            outpayment.setDifference(correction());
            List<Supplier<SSVoucher>> legacy = List.of(invoice::generateVoucher, credit::generateVoucher,
                    supplier::generateVoucher, supplierCredit::generateVoucher,
                    inpayment::generateVoucher, outpayment::generateVoucher);
            List<Supplier<SSVoucher>> explicit = List.of(
                    () -> invoice.generateVoucher(fixture.plan(), true, fixture.projects(), fixture.units()),
                    () -> credit.generateVoucher(fixture.plan(), true, fixture.projects(), fixture.units()),
                    () -> supplier.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()),
                    () -> supplierCredit.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()),
                    () -> inpayment.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()),
                    () -> outpayment.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()));
            state.context(fixture);
            List<Object> expected = legacy.stream().map(call -> snapshot(call.get())).toList();
            state.poison();
            for (int index = 0; index < explicit.size(); index++) {
                assertThat(snapshot(explicit.get(index).get())).as("booking family " + index)
                        .isEqualTo(expected.get(index));
            }
        }
    }

    @Test
    void absentCorrectionsAndDifferencesBookLikeEmptySnapshotsWithoutCreatingThem() throws Exception {
        try (LegacyState state = new LegacyState()) {
            Fixture fixture = fixture();
            SSSupplierInvoice supplier = new SSSupplierInvoice();
            supplier(supplier, fixture, 3);
            SSSupplierCreditInvoice credit = new SSSupplierCreditInvoice();
            supplier(credit, fixture, 4);
            SSInpayment incoming = new SSInpayment();
            incoming.setDefaultAccounts(fixture.defaults());
            SSOutpayment outgoing = new SSOutpayment();
            outgoing.setDefaultAccounts(fixture.defaults());
            List<Supplier<SSVoucher>> calculations = List.of(
                    () -> supplier.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()),
                    () -> credit.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()),
                    () -> incoming.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()),
                    () -> outgoing.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()));
            supplier.setCorrection(new SSVoucher(0, null));
            credit.setCorrection(new SSVoucher(0, null));
            incoming.setDifference(new SSVoucher(0, null));
            outgoing.setDifference(new SSVoucher(0, null));
            state.poison();
            List<Object> expected = calculations.stream().map(call -> snapshot(call.get())).toList();
            supplier.setCorrection(null);
            credit.setCorrection(null);
            incoming.setDifference(null);
            outgoing.setDifference(null);
            for (int index = 0; index < calculations.size(); index++) {
                assertThat(snapshot(calculations.get(index).get())).as("absent auxiliary family " + index)
                        .isEqualTo(expected.get(index));
            }
            assertThat(supplier.getCorrection()).isNull();
            assertThat(credit.getCorrection()).isNull();
            assertThat(incoming.getStoredDifference()).isNull();
            assertThat(outgoing.getStoredDifference()).isNull();
        }
    }

    @Test
    void paymentDifferencesKeepDistinctRawDimensionsInsteadOfCollapsingThroughEmptyLegacyLookups()
            throws Exception {
        try (LegacyState state = new LegacyState()) {
            Fixture fixture = fixture();
            SSInpayment incoming = new SSInpayment();
            incoming.setDefaultAccounts(fixture.defaults());
            SSOutpayment outgoing = new SSOutpayment();
            outgoing.setDefaultAccounts(fixture.defaults());
            SSVoucher difference = new SSVoucher(0, null);
            difference.addVoucherRow(row(6060, "P1", "U1", "10", null));
            difference.addVoucherRow(row(6060, "P2", "U1", null, "10"));
            incoming.setDifference(difference);
            outgoing.setDifference(new SSVoucher(difference));
            state.poison();
            for (SSVoucher generated : List.of(
                    incoming.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()),
                    outgoing.generateVoucher(fixture.plan(), fixture.projects(), fixture.units()))) {
                var rows = generated.getRows().stream().filter(row -> Integer.valueOf(6060).equals(row.getAccountNr()))
                        .toList();
                assertThat(rows).extracting(SSVoucherRow::getProjectNr).containsExactly("P1", "P2");
                assertThat(rows.get(0).getDebet()).isEqualByComparingTo("10");
                assertThat(rows.get(1).getCredit()).isEqualByComparingTo("10");
            }
        }
    }

    @Test
    void rawNonzeroPaymentDifferencesAffectControlAccountsAndRemainBalancedWithoutLegacyValidity()
            throws Exception {
        try (LegacyState state = new LegacyState()) {
            Fixture fixture = fixture();
            SSInpayment incoming = new SSInpayment();
            incoming.setDefaultAccounts(fixture.defaults());
            SSInpaymentRow inRow = new SSInpaymentRow();
            inRow.setValue(new BigDecimal("125"));
            inRow.setCurrencyRate(BigDecimal.ONE);
            inRow.setInvoiceCurrencyRate(BigDecimal.ONE);
            incoming.setRows(List.of(inRow));
            SSOutpayment outgoing = new SSOutpayment();
            outgoing.setDefaultAccounts(fixture.defaults());
            SSOutpaymentRow outRow = new SSOutpaymentRow();
            outRow.setValue(new BigDecimal("125"));
            outRow.setCurrencyRate(BigDecimal.ONE);
            outRow.setInvoiceCurrencyRate(BigDecimal.ONE);
            outgoing.setRows(List.of(outRow));
            SSVoucher difference = new SSVoucher(0, null);
            difference.addVoucherRow(row(6060, "P1", "U1", "10", null));
            incoming.setDifference(difference);
            outgoing.setDifference(new SSVoucher(difference));
            state.poison();
            SSVoucher inVoucher = incoming.generateVoucher(fixture.plan(), fixture.projects(), fixture.units());
            SSVoucher outVoucher = outgoing.generateVoucher(fixture.plan(), fixture.projects(), fixture.units());
            assertThat(inVoucher.getRows().stream().filter(row -> Integer.valueOf(1510).equals(row.getAccountNr()))
                    .findFirst().orElseThrow().getCredit()).isEqualByComparingTo("135");
            assertThat(outVoucher.getRows().stream().filter(row -> Integer.valueOf(2440).equals(row.getAccountNr()))
                    .findFirst().orElseThrow().getDebet()).isEqualByComparingTo("115");
            for (SSVoucher generated : List.of(inVoucher, outVoucher)) {
                assertThat(SSVoucherMath.getCreditMinusDebetSum(generated, fixture.plan().getAccounts()))
                        .isEqualByComparingTo("0");
            }
        }
    }

    @Test
    void normalizedVoucherReconstructionKeepsNullDatesRowsAndCorrectionLinksWithLegacyStoragePoisoned()
            throws Exception {
        try (LegacyState state = new LegacyState();
             Connection connection = java.sql.DriverManager.getConnection(
                     "jdbc:hsqldb:mem:explicit_reader_" + java.util.UUID.randomUUID() + ";shutdown=true", "sa", "")) {
            connection.setAutoCommit(false);
            new org.fribok.bookkeeping.dataformat.SchemaMigrationRunner(java.time.Clock.systemUTC())
                    .migrate(connection, org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations.load());
            var writer = new org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter(java.time.Clock.systemUTC());
            SSNewCompany company = new SSNewCompany();
            company.setId(7);
            company.setName("Typed company");
            writer.addCompany(connection, company);
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setId(44);
            SSAccountPlan plan = new SSAccountPlan("Typed plan");
            plan.getAccounts().add(new SSAccount(1930));
            plan.getAccounts().add(new SSAccount(3001));
            year.setAccountPlan(plan);
            writer.addAccountingYear(connection, company.getId(), year);
            SSVoucher original = new SSVoucher(1, null);
            long originalId = writer.addVoucher(connection, year.getId(), original);
            SSVoucher correction = new SSVoucher(42, null);
            correction.setDescription("Persisted correction");
            correction.setCorrects(original);
            correction.addVoucherRow(row(1930, null, null, "10", null));
            correction.addVoucherRow(row(3001, null, null, null, "10"));
            long correctionId = writer.addVoucher(connection, year.getId(), correction);
            // The core insertion API does not create correction links; seed the stored relation explicitly.
            try (var update = connection.prepareStatement("UPDATE voucher SET corrects_voucher_id=? WHERE id=?")) {
                update.setLong(1, originalId);
                update.setLong(2, correctionId);
                update.executeUpdate();
            }
            connection.commit();
            state.poison();
            var read = new org.fribok.bookkeeping.dataformat.NormalizedAccountingReader()
                    .vouchers(connection, year.getId());
            assertThat(read).hasSize(2);
            SSVoucher restored = read.stream().filter(voucher -> voucher.getNumber() == 42).findFirst().orElseThrow();
            assertThat(restored.getLocalDate()).isNull();
            assertThat(restored.getDescription()).isEqualTo("Persisted correction");
            assertThat(restored.getRows()).extracting(SSVoucherRow::getAccountNr).containsExactly(1930, 3001);
            assertThat(restored.getCorrects().getNumber()).isEqualTo(1);
        }
    }

    private Fixture fixture() {
        Map<SSDefaultAccount, Integer> defaults = new LinkedHashMap<>();
        SSAccountPlan plan = new SSAccountPlan("Explicit plan");
        for (SSDefaultAccount key : SSDefaultAccount.values()) {
            defaults.put(key, key.getDefaultAccountNumber());
            if (plan.getAccount(key.getDefaultAccountNumber()) == null) {
                plan.addAccount(new SSAccount(key.getDefaultAccountNumber()));
            }
        }
        plan.addAccount(new SSAccount(6060));
        plan.addAccount(new SSAccount(6070));
        SSNewProject p1 = new SSNewProject(); p1.setNumber("P1");
        SSNewProject p2 = new SSNewProject(); p2.setNumber("P2");
        SSNewResultUnit unit = new SSNewResultUnit(); unit.setNumber("U1");
        return new Fixture(plan, List.of(p1, p2), List.of(unit), defaults);
    }

    private <T extends SSInvoice> T sale(T invoice, Fixture fixture, int number) {
        invoice.setNumber(number);
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.setDefaultAccounts(fixture.defaults());
        SSSaleRow row = new SSSaleRow();
        row.setDescription("Sale");
        row.setUnitprice(new BigDecimal("100"));
        row.setQuantity(1);
        row.setAccountNr(3001);
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        row.setProjectNr("P1");
        row.setResultUnitNr("U1");
        invoice.setRows(List.of(row));
        return invoice;
    }

    private <T extends SSSupplierInvoice> T supplier(T invoice, Fixture fixture, int number) {
        invoice.setNumber(number);
        invoice.setDefaultAccounts(fixture.defaults());
        invoice.setTaxSum(new BigDecimal("25"));
        invoice.setRoundingSum(BigDecimal.ZERO);
        invoice.setCurrencyRate(BigDecimal.ONE);
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setUnitprice(new BigDecimal("100"));
        row.setQuantity(1);
        row.setAccountNr(4010);
        row.setProjectNr("P1");
        row.setResultUnitNr("U1");
        invoice.setRows(List.of(row));
        invoice.setCorrection(correction());
        return invoice;
    }

    private SSVoucher correction() {
        SSVoucher voucher = new SSVoucher(0, null);
        voucher.addVoucherRow(row(6060, "P1", "U1", "10", null));
        voucher.addVoucherRow(row(6070, "P2", "U1", null, "10"));
        return voucher;
    }

    private SSVoucherRow row(int account, String project, String unit, String debit, String credit) {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(account);
        row.setProjectNr(project);
        row.setResultUnitNr(unit);
        row.setDebet(debit == null ? null : new BigDecimal(debit));
        row.setCredit(credit == null ? null : new BigDecimal(credit));
        return row;
    }

    private Object snapshot(SSVoucher voucher) {
        List<Object> rows = new ArrayList<>();
        for (SSVoucherRow row : voucher.getRows()) {
            rows.add(java.util.Arrays.asList(row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr(),
                    decimal(row.getDebet()), decimal(row.getCredit()), row.isCrossed(), row.isAdded(),
                    row.getLocalEditedDate(), row.getEditedSignature()));
        }
        return java.util.Arrays.asList(voucher.getNumber(), voucher.getLocalDate(), voucher.getDescription(), rows);
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** Scoped poison catches legacy JDBC, numbering/date defaults and dimension/account getters. */
    private static final class LegacyState implements AutoCloseable {
        private final SSDB db = SSDB.getInstance();
        private final Map<Field, Object> saved = new LinkedHashMap<>();
        private final Connection legacy = java.sql.DriverManager.getConnection(
                "jdbc:hsqldb:mem:explicit_context_" + java.util.UUID.randomUUID(), "sa", "");

        LegacyState() throws Exception {
            for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers")) {
                Field field = SSDB.class.getDeclaredField(name);
                field.setAccessible(true);
                saved.put(field, field.get(db));
            }
            set("iCurrentCompany", null);
            set("iCurrentYear", null);
            set("iVouchers", List.of());
            try (var statement = legacy.createStatement()) {
                statement.execute("CREATE TABLE tbl_company (id INTEGER PRIMARY KEY, company OTHER)");
                statement.execute("CREATE TABLE tbl_project (companyid INTEGER, project OTHER)");
                statement.execute("CREATE TABLE tbl_resultunit (companyid INTEGER, resultunit OTHER)");
            }
            set("iConnection", legacy);
        }

        void context(Fixture fixture) throws Exception {
            SSNewCompany company = new SSNewCompany();
            company.setRoundingOff(true);
            company.setId(1);
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setAccountPlan(fixture.plan());
            set("iCurrentCompany", company);
            set("iCurrentYear", year);
            set("iVouchers", List.of());
            set("iConnection", legacy);
            try (var statement = legacy.createStatement()) {
                statement.executeUpdate("DELETE FROM tbl_company");
                statement.executeUpdate("DELETE FROM tbl_project");
                statement.executeUpdate("DELETE FROM tbl_resultunit");
            }
            try (var insert = legacy.prepareStatement("INSERT INTO tbl_company VALUES (1,?)")) {
                insert.setObject(1, company);
                insert.executeUpdate();
            }
            for (var project : fixture.projects()) {
                try (var insert = legacy.prepareStatement("INSERT INTO tbl_project VALUES (1,?)")) {
                    insert.setObject(1, project);
                    insert.executeUpdate();
                }
            }
            for (var unit : fixture.units()) {
                try (var insert = legacy.prepareStatement("INSERT INTO tbl_resultunit VALUES (1,?)")) {
                    insert.setObject(1, unit);
                    insert.executeUpdate();
                }
            }
        }

        void poison() throws Exception {
            SSNewAccountingYear year = new SSNewAccountingYear() {
                @Override public SSAccountPlan getAccountPlan() {
                    throw new AssertionError("Unexpected legacy account-plan lookup");
                }
                @Override public List<SSAccount> getAccounts() {
                    throw new AssertionError("Unexpected legacy account lookup");
                }
            };
            set("iCurrentCompany", new SSNewCompany());
            set("iCurrentYear", year);
            set("iVouchers", null);
            set("iConnection", Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }

        private void set(String name, Object value) throws Exception {
            Field field = SSDB.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(db, value);
        }

        @Override public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(db, entry.getValue());
            try (var statement = legacy.createStatement()) {
                statement.execute("SHUTDOWN");
            }
            legacy.close();
        }
    }

    private record Fixture(SSAccountPlan plan, List<SSNewProject> projects,
                           List<SSNewResultUnit> units, Map<SSDefaultAccount, Integer> defaults) {}
}
