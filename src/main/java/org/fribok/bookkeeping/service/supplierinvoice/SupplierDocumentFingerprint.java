package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** Typed source-graph token for confirmed supplier document workflows. */
final class SupplierDocumentFingerprint {
    static void write(
            CanonicalFingerprintWriter writer, Connection connection, boolean credit, long id)
            throws SQLException {
        String prefix = credit ? "supplier_credit_invoice" : "supplier_invoice";
        String owner = prefix + "_id";
        signature(writer, connection, prefix, "id", id);
        for (String suffix : List.of("_default_account", "_row", "_voucher", "_voucher_row")) {
            signature(writer, connection, prefix + suffix, owner, id);
        }
    }

    private static void signature(
            CanonicalFingerprintWriter writer,
            Connection connection,
            String table,
            String owner,
            long id)
            throws SQLException {
        writer.writeBoundary(table);
        // Sort per-row fingerprints to retain multiplicity without relying on JDBC row order.
        try (var statement =
                connection.prepareStatement("SELECT * FROM " + table + " WHERE " + owner + "=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                var metadata = rows.getMetaData();
                var signatures = new java.util.ArrayList<String>();
                while (rows.next()) {
                    var row = new CanonicalFingerprintWriter();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        int type = metadata.getColumnType(i);
                        if (!List.of(
                                        Types.BIGINT,
                                        Types.INTEGER,
                                        Types.SMALLINT,
                                        Types.TINYINT,
                                        Types.DECIMAL,
                                        Types.NUMERIC,
                                        Types.BOOLEAN,
                                        Types.BIT,
                                        Types.VARCHAR,
                                        Types.CHAR,
                                        Types.LONGVARCHAR,
                                        Types.DATE,
                                        Types.TIMESTAMP,
                                        Types.TIMESTAMP_WITH_TIMEZONE)
                                .contains(type)) {
                            throw new SQLException("Unsupported deletion SQL type " + type);
                        }
                        row.writeString(metadata.getColumnName(i))
                                .writeInteger(type)
                                .writeString(rows.getString(i));
                    }
                    signatures.add(row.finish());
                }
                java.util.Collections.sort(signatures);
                for (String row : signatures) {
                    writer.writeString(row);
                }
            }
        }
    }
}
