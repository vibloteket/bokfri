package org.fribok.bookkeeping.service.vat;

import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.DAY;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.account;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.accounts;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.voucher;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.year;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.vat.VatSettlementDiagnostics.Kind;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

class VatSettlementDiagnosticsTest {
    static final class StrictRow extends SSVoucherRow {
        StrictRow(Integer number, String debit, String credit, String project, String unit) {
            setAccountNr(number);
            setDebet(debit == null ? null : new BigDecimal(debit));
            setCredit(credit == null ? null : new BigDecimal(credit));
            setProjectNr(project);
            setResultUnitNr(unit);
        }

        @Override
        public SSAccount getAccount() {
            throw new AssertionError("ambient account");
        }

        @Override
        public SSAccount getAccount(List<SSAccount> a) {
            throw new AssertionError("mutating account");
        }

        @Override
        public boolean isValid() {
            throw new AssertionError("ambient validity");
        }

        @Override
        public SSNewProject getProject() {
            throw new AssertionError("ambient project");
        }

        @Override
        public SSNewProject getProject(List<SSNewProject> p) {
            throw new AssertionError("mutating project");
        }

        @Override
        public SSNewResultUnit getResultUnit() {
            throw new AssertionError("ambient unit");
        }

        @Override
        public SSNewResultUnit getResultUnit(List<SSNewResultUnit> u) {
            throw new AssertionError("mutating unit");
        }
    }

    static VatSettlementPlan plan(List<SSAccount> a, SSVoucher... sources) {
        return new VatService(
                        year(a, sources),
                        v -> {
                            throw new AssertionError("writer");
                        })
                .declarationPlan(DAY, DAY);
    }

    static List<VatSettlementDiagnostics.Finding> inspect(List<SSAccount> a, SSVoucher... sources) {
        var p = plan(a, sources);
        return VatSettlementDiagnostics.inspect(p.report(), a, List.of(sources), p.voucher());
    }

    @Test
    void proposedAccountFlagsAreObservedWithoutInventingDimensionsAndUnusedRolesAreIgnoredTest() {
        var a = accounts();
        a.get(0).setActive(false);
        a.get(0).setProjectRequired(true);
        a.get(0).setResultUnitRequired(true);
        a.get(2).setActive(false); // Unused receivable must not be diagnosed as a proposed row.
        a.get(3).setActive(false);
        a.get(4).setResultUnitRequired(true);
        var findings = inspect(a, voucher(DAY, new StrictRow(2610, null, "1.99", null, null)));
        assertEquals(
                List.of(
                        Kind.INACTIVE_ACCOUNT,
                        Kind.REQUIRED_PROJECT_MISSING,
                        Kind.REQUIRED_UNIT_MISSING,
                        Kind.INACTIVE_ACCOUNT,
                        Kind.REQUIRED_UNIT_MISSING),
                findings.stream().map(VatSettlementDiagnostics.Finding::kind).toList());
        assertEquals(
                List.of(2610, 2610, 2610, 2650, 3740),
                findings.stream().map(VatSettlementDiagnostics.Finding::account).toList());
        assertTrue(
                findings.stream()
                        .allMatch(
                                f ->
                                        f.sourceVoucher() == null
                                                && f.sourceRow() == null
                                                && f.dimension() == null));
    }

    @Test
    void optionalAndBlankRawSourceDimensionKeysAreNotSilentlyTreatedAsAbsentTest() {
        var first = new StrictRow(2610, null, "1.25", "<html>raw\nP", null);
        var second = new StrictRow(2610, null, "0.25", "", " \t");
        var findings = inspect(accounts(), voucher(DAY, first, second));
        assertEquals(3, findings.size());
        assertEquals("<html>raw\nP", findings.get(0).dimension());
        assertEquals("", findings.get(1).dimension());
        assertEquals(" \t", findings.get(2).dimension());
        assertEquals(
                List.of(1, 2, 2),
                findings.stream().map(VatSettlementDiagnostics.Finding::sourceRow).toList());
        assertTrue(findings.stream().allMatch(f -> f.sourceVoucher() == 7));
        assertEquals("<html>raw\nP", first.getProjectNr());
    }

    @Test
    void offsettingAccountBalancesStillExposeDistinctDimensionSourcesWhenProposalIsEmptyTest() {
        var a = accounts();
        var source =
                voucher(
                        DAY,
                        new StrictRow(2610, null, "10", "P1", "U1"),
                        new StrictRow(2610, null, "-10", "P2", "U2"));
        var p = plan(a, source);
        assertTrue(p.voucher().getRows().isEmpty());
        var findings =
                VatSettlementDiagnostics.inspect(p.report(), a, List.of(source), p.voucher());
        assertEquals(4, findings.size());
        assertEquals(
                List.of("P1", "U1", "P2", "U2"),
                findings.stream().map(VatSettlementDiagnostics.Finding::dimension).toList());
        assertEquals(0, p.report().vatToPayOrRefund().signum());
    }

    @Test
    void crossedUnknownNullEmptyZeroBaseAndOutOfPeriodDimensionRowsAreExcludedTest() {
        var a = new ArrayList<>(accounts());
        a.add(account(3010, "MP1"));
        var crossed = new StrictRow(2610, null, "100", "P", "U");
        crossed.setCrossed(true);
        var current =
                voucher(
                        DAY,
                        new StrictRow(2610, null, "1", null, null),
                        crossed,
                        new StrictRow(9999, null, "100", "P", "U"),
                        new StrictRow(null, null, "100", "P", "U"),
                        new StrictRow(2610, null, null, "P", "U"),
                        new StrictRow(2610, "0", null, "P", "U"),
                        new StrictRow(3010, null, "100", "P", "U"));
        var prior = voucher(DAY.minusDays(1), new StrictRow(2610, null, "100", "P", "U"));
        var undated = voucher(null, new StrictRow(2610, null, "100", "P", "U"));
        assertTrue(inspect(a, current, prior, undated).isEmpty());
        assertTrue(crossed.isCrossed());
        assertEquals("P", crossed.getProjectNr());
    }

    @Test
    void everySupportedTaxCodeIncludingMiGroupsUsesReportFieldInventoryTest() {
        var a = new ArrayList<>(accounts());
        var rows = new ArrayList<SSVoucherRow>();
        String[] codes = {
            "U1", "UVL", "U2", "U3", "U1MI", "UEU", "UTFU", "U2MI", "U3MI", "UI1", "UI2", "UI3",
            "I", "IVL"
        };
        for (int i = 0; i < codes.length; i++) {
            a.add(account(4000 + i, codes[i]));
            rows.add(new StrictRow(4000 + i, null, "1.99", "P" + i, null));
        }
        var findings = inspect(a, voucher(DAY, rows.toArray(SSVoucherRow[]::new)));
        assertEquals(14, findings.size());
        for (int i = 0; i < 14; i++) {
            assertEquals(4000 + i, findings.get(i).account());
            assertEquals("P" + i, findings.get(i).dimension());
            assertEquals(Kind.SOURCE_PROJECT_NOT_CARRIED, findings.get(i).kind());
        }
    }

    @Test
    void strictRawGettersNeverResolveAccountsProjectsUnitsOrValidityThroughLegacyStateTest() {
        var a = accounts();
        a.get(0).setProjectRequired(true);
        a.get(0).setResultUnitRequired(true);
        var source = voucher(DAY, new StrictRow(2610, null, "1", "P", "U"));
        var p = plan(a, source);
        var proposed = new SSVoucher(0, DAY);
        proposed.setVoucherRows(
                new ArrayList<>(List.of(new StrictRow(2610, "1", null, null, null))));
        var findings = VatSettlementDiagnostics.inspect(p.report(), a, List.of(source), proposed);
        assertEquals(
                List.of(
                        Kind.REQUIRED_PROJECT_MISSING,
                        Kind.REQUIRED_UNIT_MISSING,
                        Kind.SOURCE_PROJECT_NOT_CARRIED,
                        Kind.SOURCE_UNIT_NOT_CARRIED),
                findings.stream().map(VatSettlementDiagnostics.Finding::kind).toList());
    }

    @Test
    void returnedScalarFindingsStayImmutableAfterLaterSourceAndAccountEditsTest() {
        var a = accounts();
        var row = new StrictRow(2610, null, "1.25", "P1", null);
        var source = voucher(DAY, row);
        var old = inspect(a, source);
        assertThrows(UnsupportedOperationException.class, old::clear);
        row.setProjectNr("P2");
        a.get(0).setActive(false);
        var fresh = inspect(a, source);
        assertEquals("P1", old.get(0).dimension());
        assertEquals(1, old.size());
        assertEquals(2, fresh.size());
        assertEquals("P2", fresh.get(1).dimension());
        assertEquals(0, new BigDecimal("1.25").compareTo(row.getCredit()));
    }

    @Test
    void malformedCataloguePeriodAndProposalReferencesFailWithoutAmbientFallbackTest() {
        var a = accounts();
        var p = plan(a);
        assertThrows(
                NullPointerException.class,
                () -> VatSettlementDiagnostics.inspect(p.report(), null, List.of(), p.voucher()));
        var duplicate = new ArrayList<>(a);
        duplicate.add(account(2610, "U2"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VatSettlementDiagnostics.inspect(
                                p.report(), duplicate, List.of(), p.voucher()));
        var invalid = new ArrayList<>(a);
        invalid.add(null);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VatSettlementDiagnostics.inspect(
                                p.report(), invalid, List.of(), p.voucher()));
        var unknown = new SSVoucher(0, DAY);
        unknown.setVoucherRows(
                new ArrayList<>(List.of(new StrictRow(9999, "1", null, null, null))));
        assertThrows(
                IllegalArgumentException.class,
                () -> VatSettlementDiagnostics.inspect(p.report(), a, List.of(), unknown));
        var dated = new SSVoucher(0, DAY.plusDays(1));
        assertThrows(
                IllegalArgumentException.class,
                () -> VatSettlementDiagnostics.inspect(p.report(), a, List.of(), dated));
        var numbered = new SSVoucher(1, DAY);
        assertThrows(
                IllegalArgumentException.class,
                () -> VatSettlementDiagnostics.inspect(p.report(), a, List.of(), numbered));
        var invalidReport = new VatReport(null, DAY, p.report().boxes(), BigDecimal.ZERO);
        assertThrows(
                IllegalArgumentException.class,
                () -> VatSettlementDiagnostics.inspect(invalidReport, a, List.of(), p.voucher()));
    }
}
