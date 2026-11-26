package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class NormalizedVatSettlementSourceSnapshotTest {
    @Test
    void snapshotCopiesBothLevelsAndRetainsSqlNullSeparatelyFromEmptyAndLiteralNullTest() {
        var row = new ArrayList<>(Arrays.asList(null, "", "null", " "));
        var rows = new ArrayList<List<String>>(List.of(row));
        var snapshot = new NormalizedVatSettlementSourceSnapshot(rows);
        row.clear();
        rows.clear();
        assertEquals(Arrays.asList(null, "", "null", " "), snapshot.rows().get(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows().get(0).clear());
    }

    @Test
    void typedRowStructureDoesNotCollideOnDelimitersNewlinesOrEmptyValuesTest() {
        assertNotEquals(
                new NormalizedVatSettlementSourceSnapshot(List.of(List.of("a|b", "c"))),
                new NormalizedVatSettlementSourceSnapshot(List.of(List.of("a", "b|c"))));
        assertNotEquals(
                new NormalizedVatSettlementSourceSnapshot(List.of(List.of("a\nb"))),
                new NormalizedVatSettlementSourceSnapshot(List.of(List.of("a", "b"))));
        assertNotEquals(
                new NormalizedVatSettlementSourceSnapshot(List.of(Arrays.asList((String) null))),
                new NormalizedVatSettlementSourceSnapshot(List.of(List.of(""))));
    }

    @Test
    void equalityRequiresExactOrderedTablesRowsAndValuesNotOnlyTotalsOrHashesTest() {
        var a =
                new NormalizedVatSettlementSourceSnapshot(
                        List.of(List.of("row", "4", "1", "2"), List.of("row", "4", "2", "1")));
        assertEquals(a, new NormalizedVatSettlementSourceSnapshot(a.rows()));
        assertNotEquals(
                a,
                new NormalizedVatSettlementSourceSnapshot(
                        List.of(List.of("row", "4", "1", "1"), List.of("row", "4", "2", "2"))));
        assertNotEquals(
                a,
                new NormalizedVatSettlementSourceSnapshot(
                        List.of(a.rows().get(1), a.rows().get(0))));
        assertNotEquals(
                a,
                new NormalizedVatSettlementSourceSnapshot(
                        List.of(List.of("row", "5", "1", "2"), a.rows().get(1))));
    }

    @Test
    void malformedContainerInputsFailWithoutInventingEmptySourceStateTest() {
        assertThrows(
                NullPointerException.class, () -> new NormalizedVatSettlementSourceSnapshot(null));
        assertThrows(
                NullPointerException.class,
                () ->
                        new NormalizedVatSettlementSourceSnapshot(
                                Arrays.asList((List<String>) null)));
    }
}
