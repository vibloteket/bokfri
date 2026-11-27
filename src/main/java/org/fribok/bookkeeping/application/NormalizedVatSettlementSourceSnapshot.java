package org.fribok.bookkeeping.application;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private read-review state, not a persistence format or posting authorization. */
record NormalizedVatSettlementSourceSnapshot(List<List<String>> rows) {
    NormalizedVatSettlementSourceSnapshot {
        var copy = new ArrayList<List<String>>();
        for (var row : rows) {
            // SQL null is distinct from every raw string. No mutable JDBC objects are retained.
            copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = List.copyOf(copy);
    }

    /** Captures conservative current-year inputs on the caller's existing read connection. */
    static NormalizedVatSettlementSourceSnapshot capture(
            Connection connection, int company, int year) throws SQLException {
        String[] queries = {
            "SELECT * FROM company WHERE legacy_id=?",
            "SELECT * FROM accounting_year WHERE legacy_id=?",
            "SELECT * FROM account WHERE accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=?) ORDER BY number",
            "SELECT * FROM opening_balance WHERE accounting_year_id=(SELECT id FROM accounting_year"
                    + " WHERE legacy_id=?) ORDER BY account_id",
            "SELECT * FROM voucher WHERE accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=?) ORDER BY id",
            "SELECT * FROM voucher_row WHERE accounting_year_id=(SELECT id FROM accounting_year"
                    + " WHERE legacy_id=?) ORDER BY voucher_id,row_number",
            "SELECT * FROM project WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                    + " ORDER BY number",
            "SELECT * FROM result_unit WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                    + " ORDER BY number",
            "SELECT * FROM account_plan WHERE accounting_year_id=(SELECT id FROM accounting_year"
                + " WHERE legacy_id=?) ORDER BY id"
        };
        var result = new ArrayList<List<String>>();
        for (int index = 0; index < queries.length; index++) {
            try (var query =
                    connection.prepareStatement("/* VAT source review */ " + queries[index])) {
                query.setInt(1, index == 0 || index == 6 || index == 7 ? company : year);
                try (var rows = query.executeQuery()) {
                    var metadata = rows.getMetaData();
                    var header = new ArrayList<String>();
                    header.add("table");
                    header.add(Integer.toString(index));
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        header.add(metadata.getColumnName(column));
                        header.add(Integer.toString(metadata.getColumnType(column)));
                    }
                    result.add(header);
                    while (rows.next()) {
                        var values = new ArrayList<String>();
                        values.add("row");
                        values.add(Integer.toString(index));
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            values.add(rows.getString(column));
                        }
                        result.add(values);
                    }
                }
            }
        }
        return new NormalizedVatSettlementSourceSnapshot(result);
    }
}
