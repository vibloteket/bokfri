package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.money;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.accounts;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.prepared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.service.vat.VatSettlementAmounts.Kind;
import org.fribok.bookkeeping.service.vat.VatSettlementAmounts.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class NormalizedApplicationVatSettlementAmountsTest {
    static final String LIMIT = "9".repeat(70);

    static void sql(Fixture f, String... statements) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            for (String q : statements) s.executeUpdate(q);
        }
    }

    static void fractional(Fixture f) throws Exception {
        sql(
                f,
                "UPDATE voucher_row SET credit=0.991 WHERE account_id=(SELECT id FROM account WHERE"
                    + " number=2610 AND accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=11))",
                "UPDATE voucher_row SET debit=1.981 WHERE account_id=(SELECT id FROM account WHERE"
                    + " number=1910 AND accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=11))");
    }

    static void repair(Fixture f) throws Exception {
        sql(
                f,
                "UPDATE voucher_row SET credit=0.99 WHERE account_id=(SELECT id FROM account WHERE"
                    + " number=2610 AND accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=11))",
                "UPDATE voucher_row SET debit=1.98 WHERE account_id=(SELECT id FROM account WHERE"
                    + " number=1910 AND accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=11))");
    }

    static void source(Fixture f, int number, boolean credit, String project) throws Exception {
        sql(
                f,
                "INSERT INTO voucher(accounting_year_id,number,voucher_date,description) SELECT id,"
                        + number
                        + ",DATE '2026-06-15','Large exact source' FROM accounting_year WHERE"
                        + " legacy_id=11");
        for (int index = 0; index < 2; index++) {
            boolean debit = index == 0 ? credit : !credit;
            int account = index == 0 ? 1910 : 2610;
            sql(
                    f,
                    "INSERT INTO"
                        + " voucher_row(voucher_id,accounting_year_id,row_number,account_id,project_number,debit,credit,crossed,added)"
                        + " SELECT v.id,v.accounting_year_id,"
                            + index
                            + ",a.id,"
                            + (index == 1 && project != null ? "'" + project + "'" : "NULL")
                            + ","
                            + (debit ? LIMIT + ",NULL" : "NULL," + LIMIT)
                            + ",FALSE,FALSE FROM voucher v JOIN account a ON"
                            + " a.accounting_year_id=v.accounting_year_id AND a.number="
                            + account
                            + " WHERE v.number="
                            + number
                            + " AND v.accounting_year_id=(SELECT id FROM accounting_year WHERE"
                            + " legacy_id=11)");
        }
    }

    static void totalsOnly(Fixture f) throws Exception {
        accounts(f);
        for (int n = 1; n <= 4; n++) {
            String project = "P" + n;
            sql(
                    f,
                    "INSERT INTO project(company_id,number,name,description,concluded) SELECT id,'"
                            + project
                            + "','Local','Local',FALSE FROM company WHERE legacy_id=71");
            source(f, n, n <= 2, project);
        }
    }

    @Test
    void fractionalStoredSourceProducesExactClearingAndRoundingFindingsWithoutWritesOrRoundingTest()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            fractional(f);
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = observed.owner.select(71, 11).reports();
                observed.reset();
                var p = reports.vatSettlementPreview(FROM, TO);
                assertEquals(
                        List.of(Kind.FRACTIONAL_CENT, Kind.FRACTIONAL_CENT),
                        p.amountFindings().stream().map(x -> x.kind()).toList());
                assertEquals(
                        List.of(1, 4),
                        p.amountFindings().stream().map(x -> x.proposedRow()).toList());
                assertEquals(
                        List.of(2610, 3740),
                        p.amountFindings().stream().map(x -> x.account()).toList());
                assertEquals(
                        List.of(Side.DEBIT, Side.CREDIT),
                        p.amountFindings().stream().map(x -> x.side()).toList());
                money("0.991", p.amountFindings().get(0).amount());
                money("0.981", p.amountFindings().get(1).amount());
                money("1.981", p.debitTotal());
                money("1.981", p.creditTotal());
                money("1", p.report().vatToPayOrRefund());
                assertEquals(0, observed.commits.get());
                assertEquals(2, observed.rollbacks.get());
                assertEquals(1, observed.closes.get());
                assertEquals(before, f.snapshot());
                assertTrue(
                        observed.owner
                                .select(72, 12)
                                .reports()
                                .vatSettlementPreview(FROM, TO)
                                .amountFindings()
                                .isEmpty());
            }
        }
    }

    @Test
    void exactGroupedClearingAndControlCanExceedSqlRowRangeEvenWhenEverySourceSideFitsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            source(f, 1, true, null);
            source(f, 2, true, null);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var p = owner.select(71, 11).reports().vatSettlementPreview(FROM, TO);
                assertEquals(2, p.rows().size());
                assertEquals(
                        List.of(
                                Kind.STORAGE_RANGE,
                                Kind.STORAGE_RANGE,
                                Kind.TOTAL_ENTRY_RANGE,
                                Kind.TOTAL_ENTRY_RANGE),
                        p.amountFindings().stream().map(x -> x.kind()).toList());
                BigDecimal amount = new BigDecimal(LIMIT).multiply(BigDecimal.valueOf(2));
                assertTrue(
                        p.amountFindings().stream()
                                .allMatch(x -> x.amount().compareTo(amount) == 0));
                assertEquals(0, p.debitTotal().compareTo(p.creditTotal()));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void distinctGroupsMayFitEachSqlSideButExceedExistingManualEntryAggregateRangeTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            totalsOnly(f);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var p = owner.select(71, 11).reports().vatSettlementPreview(FROM, TO);
                assertEquals(4, p.rows().size());
                money("0", p.report().vatToPayOrRefund());
                assertEquals(2, p.amountFindings().size());
                assertTrue(
                        p.amountFindings().stream()
                                .allMatch(
                                        x ->
                                                x.kind() == Kind.TOTAL_ENTRY_RANGE
                                                        && x.proposedRow() == null
                                                        && x.account() == null));
                assertEquals(
                        List.of(Side.DEBIT, Side.CREDIT),
                        p.amountFindings().stream().map(x -> x.side()).toList());
                assertTrue(p.findings().isEmpty());
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void sourceReviewAndAmountFindingsRemainFrozenAfterRepairWhileFreshReadHasNoAmountFindingsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            fractional(f);
            var reports = owner.select(71, 11).reports();
            var review = reports.vatSettlementReview(FROM, TO);
            var old = review.preview();
            repair(f);
            var before = f.snapshot();
            var fresh = reports.vatSettlementPreview(FROM, TO);
            assertEquals(2, old.amountFindings().size());
            assertTrue(fresh.amountFindings().isEmpty());
            money("0.991", old.rows().get(0).debit());
            money("0.99", fresh.rows().get(0).debit());
            assertEquals(
                    NormalizedFinancialReports.VatSettlementSourceState.CHANGED,
                    reports.checkVatSettlementSources(review));
            assertThrows(UnsupportedOperationException.class, () -> old.amountFindings().clear());
            assertEquals(before, f.snapshot());
        }
    }
}
