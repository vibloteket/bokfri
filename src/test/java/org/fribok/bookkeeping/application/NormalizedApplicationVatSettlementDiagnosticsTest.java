package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.money;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.accounts;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.prepared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Draft;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Row;
import org.fribok.bookkeeping.service.vat.VatSettlementDiagnostics.Kind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

class NormalizedApplicationVatSettlementDiagnosticsTest {
    static void flags(Fixture f) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE account SET"
                        + " active=FALSE,project_required=TRUE,result_unit_required=TRUE WHERE"
                        + " number=2610 AND accounting_year_id=(SELECT id FROM accounting_year"
                        + " WHERE legacy_id=11)");
            s.executeUpdate(
                    "UPDATE account SET active=FALSE WHERE number IN (1650,2650) AND"
                        + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                        + " legacy_id=11)");
            s.executeUpdate(
                    "UPDATE account SET project_required=TRUE WHERE number=3740 AND"
                        + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                        + " legacy_id=11)");
        }
    }

    static void dimensions(Fixture f) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            for (String number : List.of("P1", "P2"))
                s.executeUpdate(
                        "INSERT INTO project(company_id,number,name,description,concluded) SELECT"
                            + " id,'"
                                + number
                                + "','Local project','Local',FALSE FROM company WHERE"
                                + " legacy_id=71");
            s.executeUpdate(
                    "INSERT INTO result_unit(company_id,number,name,description) SELECT"
                        + " id,'U1','Local unit','Local' FROM company WHERE legacy_id=71");
        }
    }

    static Draft dimensionDraft(LocalDate date, boolean reversal, int taxAccount, String project) {
        return new Draft(
                date,
                "Dimension source",
                List.of(
                        new Row(
                                1910,
                                reversal ? null : BigDecimal.TEN,
                                reversal ? BigDecimal.TEN : null),
                        new Row(
                                taxAccount,
                                reversal ? BigDecimal.TEN : null,
                                reversal ? null : BigDecimal.TEN,
                                project,
                                "U1")));
    }

    static void offsetting(Fixture f, NormalizedApplicationOwner owner) throws Exception {
        accounts(f);
        dimensions(f);
        var commands = owner.select(71, 11).vouchers();
        for (var draft :
                List.of(
                        dimensionDraft(DAY, false, 2610, "P1"),
                        dimensionDraft(DAY, true, 2610, "P2")))
            commands.post(commands.preview(draft));
    }

    @Test
    void ownerCapturesCurrentAccountFlagsInSameReadWithoutChangingProposalOrAnySqlTest()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            flags(f);
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = observed.owner.select(71, 11).reports();
                observed.reset();
                var preview = reports.vatSettlementPreview(FROM, TO);
                assertEquals(5, preview.findings().size());
                assertEquals(
                        List.of(2610, 2610, 2610, 2650, 3740),
                        preview.findings().stream().map(v -> v.account()).toList());
                assertEquals(
                        List.of(
                                Kind.INACTIVE_ACCOUNT,
                                Kind.REQUIRED_PROJECT_MISSING,
                                Kind.REQUIRED_UNIT_MISSING,
                                Kind.INACTIVE_ACCOUNT,
                                Kind.REQUIRED_PROJECT_MISSING),
                        preview.findings().stream().map(v -> v.kind()).toList());
                assertEquals(4, preview.rows().size());
                money("1", preview.report().vatToPayOrRefund());
                money("1.98", preview.debitTotal());
                money("1.98", preview.creditTotal());
                assertThrows(UnsupportedOperationException.class, () -> preview.findings().clear());
                assertEquals(before, f.snapshot());
                assertEquals(0, observed.commits.get());
                assertEquals(2, observed.rollbacks.get());
                assertEquals(1, observed.closes.get());
            }
        }
    }

    @Test
    void sqlDimensionSourcesRemainVisibleEvenWhenAccountNetAndProposalAreZeroTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            offsetting(f, owner);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var preview = owner.select(71, 11).reports().vatSettlementPreview(FROM, TO);
                assertTrue(preview.rows().isEmpty());
                money("0", preview.report().vatToPayOrRefund());
                assertEquals(4, preview.findings().size());
                assertEquals(
                        List.of("P1", "U1", "P2", "U1"),
                        preview.findings().stream().map(v -> v.dimension()).toList());
                assertEquals(
                        List.of(1, 1, 2, 2),
                        preview.findings().stream().map(v -> v.sourceVoucher()).toList());
                assertTrue(
                        preview.findings().stream()
                                .allMatch(v -> v.sourceRow() == 2 && v.account() == 2610));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void sourcePeriodBaseCodesAndForeignCompanyNeverLeakDimensionObservationsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            dimensions(f);
            var commands = owner.select(71, 11).vouchers();
            for (var draft :
                    List.of(
                            dimensionDraft(LocalDate.of(2026, 5, 1), false, 2610, "P1"),
                            dimensionDraft(DAY, false, 3010, "P2")))
                commands.post(commands.preview(draft));
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = owner.select(71, 11).reports();
                assertTrue(reports.vatSettlementPreview(FROM, TO).findings().isEmpty());
                assertEquals(
                        2,
                        reports.vatSettlementPreview(
                                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1))
                                .findings()
                                .size());
                assertTrue(
                        owner.select(72, 12)
                                .reports()
                                .vatSettlementPreview(FROM, TO)
                                .findings()
                                .isEmpty());
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void oldFindingsStayFrozenAfterMetadataRepairAndFreshReadDoesNotGrantPostingApprovalTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            flags(f);
            var reports = owner.select(71, 11).reports();
            var old = reports.vatSettlementPreview(FROM, TO);
            try (var c = f.open();
                    var s = c.createStatement()) {
                s.executeUpdate(
                        "UPDATE account SET"
                            + " active=TRUE,project_required=FALSE,result_unit_required=FALSE WHERE"
                            + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                            + " legacy_id=11)");
            }
            var before = f.snapshot();
            var fresh = reports.vatSettlementPreview(FROM, TO);
            assertEquals(5, old.findings().size());
            assertTrue(fresh.findings().isEmpty());
            assertEquals(old.rows(), fresh.rows());
            assertEquals(old.report(), fresh.report());
            assertEquals(before, f.snapshot());
            // There is deliberately no readiness boolean, posting token or action in this DTO.
            assertTrue(
                    java.util.Arrays.stream(fresh.getClass().getRecordComponents())
                            .noneMatch(
                                    c ->
                                            c.getName().equals("ready")
                                                    || c.getName().equals("token")));
        }
    }
}
