package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.service.vat.VatSettlementDiagnostics.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

class NormalizedApplicationVatDimensionCatalogueTest {
    static void sql(Fixture f, String... statements) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            for (var statement : statements) s.executeUpdate(statement);
        }
    }

    static void close(Fixture f, String project, String date) throws Exception {
        sql(
                f,
                "UPDATE project SET concluded=TRUE,concluded_on="
                        + (date == null ? "NULL" : "DATE '" + date + "'")
                        + " WHERE number='"
                        + project
                        + "' AND company_id=(SELECT id FROM company WHERE legacy_id=71)");
    }

    static void foreignAndBlank(Fixture f) throws Exception {
        sql(
                f,
                "INSERT INTO project(company_id,number,name,description,concluded) SELECT"
                        + " id,'FOREIGN','Other','Other',FALSE FROM company WHERE legacy_id=72",
                "INSERT INTO result_unit(company_id,number,name,description) SELECT"
                        + " id,'FOREIGN-U','Other','Other' FROM company WHERE legacy_id=72",
                "UPDATE voucher_row SET project_number='FOREIGN',result_unit_number='FOREIGN-U'"
                        + " WHERE project_number='P1'",
                "UPDATE voucher_row SET project_number='',result_unit_number=' ' WHERE"
                        + " project_number='P2'");
    }

    @Test
    void currentCompanyClosureUsesProposalEndDateWithinSameRollbackOnlyReadTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            close(f, "P1", TO.toString());
            close(f, "P2", TO.plusDays(1).toString());
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = observed.owner.select(71, 11).reports();
                observed.reset();
                int opens = observed.opens.get();
                var p = reports.vatSettlementPreview(FROM, TO);
                assertEquals(2, p.rows().size());
                assertEquals(1, p.findings().size());
                assertEquals(Kind.PROJECT_CLOSED_ON_DATE, p.findings().get(0).kind());
                assertEquals("P1", p.findings().get(0).dimension());
                assertEquals(2610, p.findings().get(0).account());
                assertEquals(0, observed.commits.get());
                assertEquals(2, observed.rollbacks.get());
                assertEquals(opens + 1, observed.opens.get());
                assertEquals(1, observed.closes.get());
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void foreignCompanyKeysCannotRescueUnknownRawKeysAndBlankKeysAreNotRepairedTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            foreignAndBlank(f);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var p = owner.select(71, 11).reports().vatSettlementPreview(FROM, TO);
                assertEquals(
                        List.of(
                                Kind.UNKNOWN_PROJECT,
                                Kind.UNKNOWN_UNIT,
                                Kind.PROJECT_KEY_BLANK,
                                Kind.UNIT_KEY_BLANK),
                        p.findings().stream().map(x -> x.kind()).toList());
                assertEquals(
                        List.of("FOREIGN", "FOREIGN-U", "", " "),
                        p.findings().stream().map(x -> x.dimension()).toList());
                assertEquals("FOREIGN", p.rows().get(0).project());
                assertEquals("", p.rows().get(1).project());
                assertTrue(
                        p.findings().stream()
                                .allMatch(x -> x.sourceRow() == null && x.sourceVoucher() == null));
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
    void unknownClosureAndLaterRepairRefreshFindingsWithoutMutatingOldSnapshotOrAmountsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            close(f, "P1", null);
            var reports = owner.select(71, 11).reports();
            var old = reports.vatSettlementPreview(FROM, TO);
            assertEquals(Kind.PROJECT_CLOSURE_DATE_UNKNOWN, old.findings().get(0).kind());
            sql(
                    f,
                    "UPDATE project SET concluded=FALSE WHERE number='P1' AND company_id=(SELECT id"
                            + " FROM company WHERE legacy_id=71)");
            var before = f.snapshot();
            var fresh = reports.vatSettlementPreview(FROM, TO);
            assertTrue(fresh.findings().isEmpty());
            assertEquals(1, old.findings().size());
            assertEquals(old.rows(), fresh.rows());
            assertEquals(old.report(), fresh.report());
            assertEquals(old.debitTotal(), fresh.debitTotal());
            assertEquals(old.creditTotal(), fresh.creditTotal());
            assertThrows(UnsupportedOperationException.class, () -> old.findings().clear());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void malformedCompanyCatalogueFailsPreviewOnlyAndRollsBackWithoutAffectingOtherReportsTest()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, setup);
            sql(
                    f,
                    "INSERT INTO project(company_id,number,name,description,concluded) SELECT"
                        + " id,'','Malformed','Malformed',FALSE FROM company WHERE legacy_id=71");
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = observed.owner.select(71, 11).reports();
                reports.vatDeclarationReport(FROM, TO);
                reports.vatReport(FROM, TO);
                reports.accounts();
                observed.reset();
                assertThrows(
                        IllegalArgumentException.class,
                        () -> reports.vatSettlementPreview(FROM, TO));
                assertEquals(0, observed.commits.get());
                assertEquals(2, observed.rollbacks.get());
                assertEquals(1, observed.closes.get());
                assertEquals(before, f.snapshot());
            }
        }
    }
}
