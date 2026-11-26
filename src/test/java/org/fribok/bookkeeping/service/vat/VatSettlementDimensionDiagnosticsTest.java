package org.fribok.bookkeeping.service.vat;

import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.vat.VatSettlementDiagnostics.Kind;
import org.fribok.bookkeeping.service.vat.VatSettlementDiagnosticsTest.StrictRow;
import org.fribok.bookkeeping.service.vat.VatSettlementDimensionDiagnostics.Catalogue;
import org.fribok.bookkeeping.service.vat.VatSettlementDimensionDiagnostics.Project;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class VatSettlementDimensionDiagnosticsTest {
    static SSVoucher proposal(SSVoucherRow... rows) {
        var voucher = new SSVoucher(0, DAY);
        voucher.setVoucherRows(new ArrayList<>(List.of(rows)));
        return voucher;
    }

    static StrictRow row(String project, String unit) {
        return new StrictRow(2610, "1", null, project, unit);
    }

    static Catalogue catalogue(Project... projects) {
        return new Catalogue(List.of(projects), List.of("U"));
    }

    @Test
    void optionalNullKeysNeedNoIdentityAndKnownOpenKeysProduceNoFindingTest() {
        assertTrue(
                VatSettlementDimensionDiagnostics.inspect(
                                proposal(row(null, null), row("P", "U")),
                                catalogue(new Project("P", false, null)))
                        .isEmpty());
    }

    @Test
    void unknownAndBlankKeysRetainExactRawStringsWithoutResolutionOrRepairTest() {
        var p = proposal(row("<html>P\n", "U|1"), row("", " \t"), row(" P ", null));
        var findings =
                VatSettlementDimensionDiagnostics.inspect(
                        p, catalogue(new Project("P", false, null)));
        assertEquals(
                List.of(
                        Kind.UNKNOWN_PROJECT,
                        Kind.UNKNOWN_UNIT,
                        Kind.PROJECT_KEY_BLANK,
                        Kind.UNIT_KEY_BLANK,
                        Kind.UNKNOWN_PROJECT),
                findings.stream().map(f -> f.kind()).toList());
        assertEquals(
                List.of("<html>P\n", "U|1", "", " \t", " P "),
                findings.stream().map(f -> f.dimension()).toList());
        assertEquals("<html>P\n", p.getRows().get(0).getProjectNr());
    }

    @Test
    void closureUsesProposalDateIncludingExactBoundaryNotSourceHistoryTest() {
        var p = proposal(row("past", null), row("today", null), row("future", null));
        var findings =
                VatSettlementDimensionDiagnostics.inspect(
                        p,
                        catalogue(
                                new Project("past", true, DAY.minusDays(1)),
                                new Project("today", true, DAY),
                                new Project("future", true, DAY.plusDays(1))));
        assertEquals(List.of("past", "today"), findings.stream().map(f -> f.dimension()).toList());
        assertTrue(findings.stream().allMatch(f -> f.kind() == Kind.PROJECT_CLOSED_ON_DATE));
    }

    @Test
    void concludedUnknownDateIsSeparateAndOpenFlagIgnoresOldClosureDateTest() {
        var findings =
                VatSettlementDimensionDiagnostics.inspect(
                        proposal(row("unknown", null), row("open", null)),
                        catalogue(
                                new Project("unknown", true, null),
                                new Project("open", false, DAY.minusYears(1))));
        assertEquals(1, findings.size());
        assertEquals(Kind.PROJECT_CLOSURE_DATE_UNKNOWN, findings.get(0).kind());
    }

    @Test
    void identicalKeysAreCompanyScopedByExplicitCatalogueNotAnyAmbientGlobalCatalogueTest() {
        var p = proposal(row("P", "U"));
        assertTrue(
                VatSettlementDimensionDiagnostics.inspect(
                                p, catalogue(new Project("P", false, null)))
                        .isEmpty());
        var findings =
                VatSettlementDimensionDiagnostics.inspect(p, new Catalogue(List.of(), List.of()));
        assertEquals(
                List.of(Kind.UNKNOWN_PROJECT, Kind.UNKNOWN_UNIT),
                findings.stream().map(f -> f.kind()).toList());
    }

    @Test
    void findingsDeduplicateByKindAccountAndRawKeyWithoutInventedSourceReferencesTest() {
        var secondAccount = new StrictRow(2640, "1", null, "P", "U");
        var findings =
                VatSettlementDimensionDiagnostics.inspect(
                        proposal(row("P", "U"), row("P", "U"), secondAccount),
                        new Catalogue(List.of(), List.of()));
        assertEquals(4, findings.size());
        assertEquals(
                List.of(2610, 2610, 2640, 2640), findings.stream().map(f -> f.account()).toList());
        for (var f : findings) {
            assertNull(f.sourceVoucher());
            assertNull(f.sourceRow());
        }
    }

    @Test
    void snapshotsAndFindingsStayImmutableAfterInputListsAndRowsChangeTest() {
        var projects = new ArrayList<>(List.of(new Project("P", true, DAY)));
        var units = new ArrayList<>(List.of("U"));
        var c = new Catalogue(projects, units);
        var p = proposal(row("P", "missing"));
        var old = VatSettlementDimensionDiagnostics.inspect(p, c);
        projects.clear();
        units.clear();
        p.getRows().get(0).setProjectNr("other");
        assertEquals("P", c.projects().get(0).number());
        assertEquals("U", c.units().get(0));
        assertEquals("P", old.get(0).dimension());
        assertThrows(UnsupportedOperationException.class, () -> c.projects().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.units().clear());
        assertThrows(UnsupportedOperationException.class, old::clear);
    }

    @Test
    void malformedCatalogueFailsClosedIncludingDuplicateNullAndBlankKeysTest() {
        assertThrows(NullPointerException.class, () -> new Catalogue(null, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new Catalogue(Arrays.asList((Project) null), List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new Catalogue(List.of(), Arrays.asList((String) null)));
        for (String bad : Arrays.asList(null, "", " \t"))
            assertThrows(
                    IllegalArgumentException.class, () -> catalogue(new Project(bad, false, null)));
        assertThrows(IllegalArgumentException.class, () -> new Catalogue(List.of(), List.of(" ")));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalogue(new Project("P", false, null), new Project("P", true, DAY)));
        assertThrows(
                IllegalArgumentException.class, () -> new Catalogue(List.of(), List.of("U", "U")));
    }

    @Test
    void malformedProposalFailsWithoutAmbientFallbackTest() {
        var c = catalogue();
        assertThrows(
                NullPointerException.class,
                () -> VatSettlementDimensionDiagnostics.inspect(null, c));
        assertThrows(
                NullPointerException.class,
                () -> VatSettlementDimensionDiagnostics.inspect(proposal(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> VatSettlementDimensionDiagnostics.inspect(new SSVoucher(1, DAY), c));
        assertThrows(
                IllegalArgumentException.class,
                () -> VatSettlementDimensionDiagnostics.inspect(new SSVoucher(0, null), c));
        assertThrows(
                NullPointerException.class,
                () ->
                        VatSettlementDimensionDiagnostics.inspect(
                                proposal(new StrictRow(null, "1", null, "P", "U")), c));
    }

    @Test
    void noRowsProduceNoCatalogueApprovalAndNoFindingsTest() {
        var c = catalogue(new Project("closed", true, DAY));
        assertTrue(VatSettlementDimensionDiagnostics.inspect(proposal(), c).isEmpty());
        // No readiness boolean, token, writer or account/project model exists in this scalar
        // catalogue.
        assertEquals(
                List.of("projects", "units"),
                Arrays.stream(Catalogue.class.getRecordComponents())
                        .map(x -> x.getName())
                        .toList());
    }
}
