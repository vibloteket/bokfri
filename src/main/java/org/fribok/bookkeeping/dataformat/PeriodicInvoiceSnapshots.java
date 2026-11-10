package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSPeriodicInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** V20 nested periodic-invoice preservation; no commits, business defaults or SSDB lookups. */
final class PeriodicInvoiceSnapshots {
    enum Table {
        SNAPSHOT("snapshot", "invoice_index,present"),
        ADDRESS("address", "invoice_index,address_type,name,address_line_1,address_line_2,postal_code,city,country"),
        ACCOUNT("default_account", "invoice_index,account_type,account_number"),
        VOUCHER("voucher", "invoice_index,number,voucher_date,description"),
        ROW("voucher_row", "invoice_index,row_number,account_number,project_number,result_unit_number,"
                + "debit,credit,edited_at,edited_signature,crossed,added"),
        ADDED("added_state", "row_number,invoice_number,added");

        final String name;
        final List<String> columns;

        Table(String suffix, String columns) {
            name = "periodic_invoice_" + suffix;
            this.columns = List.of(columns.split(","));
        }
    }

    record Entry(Table table, int legacyId, List<Object> values) {}

    private PeriodicInvoiceSnapshots() {}

    static List<Entry> readLegacy(Connection legacy) throws SQLException {
        List<Entry> entries = new ArrayList<>();
        try (var statement = legacy.createStatement(); var rows = statement.executeQuery(
                "SELECT id,periodicinvoice FROM tbl_periodicinvoice ORDER BY id")) {
            while (rows.next()) {
                int id = rows.getInt(1);
                SSPeriodicInvoice periodic = (SSPeriodicInvoice) rows.getObject(2);
                invoice(entries, id, -1, periodic.getStoredTemplate());
                int index = 0;
                for (SSInvoice invoice : periodic.getInvoices()) invoice(entries, id, index++, invoice);
                int number = 0;
                List<Integer> keys = new ArrayList<>(periodic.getStoredAdded().keySet());
                keys.sort(Comparator.nullsFirst(Integer::compareTo));
                for (Integer key : keys) add(entries, Table.ADDED, id, number++, key,
                        periodic.getStoredAdded().get(key));
            }
        }
        return entries;
    }

    private static void invoice(List<Entry> entries, int id, int index, SSInvoice invoice) throws SQLException {
        add(entries, Table.SNAPSHOT, id, index, invoice != null);
        if (invoice == null) return;
        if (invoice.getClass() != SSInvoice.class) throw new SQLException(
                "PERIODIC_SNAPSHOT_UNSUPPORTED: invoice subtype " + invoice.getClass().getName()
                        + " in periodic " + id + "/" + index + "; refusing to discard subtype fields");
        address(entries, id, index, "invoice", invoice.getInvoiceAddress());
        address(entries, id, index, "delivery", invoice.getDeliveryAddress());
        for (var account : invoice.getDefaultAccounts().entrySet()) {
            add(entries, Table.ACCOUNT, id, index, account.getKey().name(), account.getValue());
        }
        SSVoucher voucher = invoice.getStoredVoucher();
        if (voucher == null) return;
        if (voucher.getCorrects() != null || voucher.getCorrectedBy() != null) throw new SQLException(
                "PERIODIC_SNAPSHOT_UNSUPPORTED: correction-linked voucher in periodic " + id + "/" + index
                        + "; refusing to discard a referenced voucher graph");
        add(entries, Table.VOUCHER, id, index, voucher.getNumber(), voucher.getLocalDate(), voucher.getDescription());
        int number = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            row.fixResultUnitAndProject();
            Instant edited = row.getLocalEditedDate() == null ? null
                    : LegacySwedishTimeResolver.resolve(row.getLocalEditedDate()).instant();
            add(entries, Table.ROW, id, index, number++, row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr(),
                    row.getDebet(), row.getCredit(), edited == null ? null : edited.atOffset(ZoneOffset.UTC),
                    row.getEditedSignature(), row.isCrossed(), row.isAdded());
        }
    }

    private static void address(List<Entry> entries, int id, int index, String type, SSAddress address) {
        if (address != null) add(entries, Table.ADDRESS, id, index, type, address.getName(), address.getAddress1(),
                address.getAddress2(), address.getZipCode(), address.getCity(), address.getCountry());
    }

    private static void add(List<Entry> entries, Table table, int id, Object... values) {
        entries.add(new Entry(table, id, Arrays.asList(values)));
    }

    static void write(Connection normalized, List<Entry> entries) throws SQLException {
        requireSchema(normalized);
        Map<Integer, Long> ids = new HashMap<>();
        try (var statement = normalized.createStatement(); var rows = statement.executeQuery(
                "SELECT legacy_id,id FROM periodic_invoice")) {
            while (rows.next()) ids.put(rows.getInt(1), rows.getLong(2));
        }
        // Enum order is also the FK insertion order. Instances are distinguished by ordinal, not invoice number.
        for (Table table : Table.values()) {
            for (Entry entry : entries) {
                if (entry.table() != table) continue;
                Long id = ids.get(entry.legacyId());
                if (id == null) throw new SQLException("Missing periodic invoice " + entry.legacyId());
                try (var insert = normalized.prepareStatement("INSERT INTO " + table.name
                        + " (periodic_invoice_id," + String.join(",", table.columns) + ") VALUES (?,"
                        + String.join(",", java.util.Collections.nCopies(table.columns.size(), "?")) + ")")) {
                    insert.setLong(1, id);
                    int index = 2;
                    for (Object value : entry.values()) insert.setObject(index++, value);
                    insert.executeUpdate();
                }
            }
        }
    }

    static List<Entry> readNormalized(Connection normalized) throws SQLException {
        requireSchema(normalized);
        validateCoverage(normalized);
        List<Entry> entries = new ArrayList<>();
        for (Table table : Table.values()) {
            String columns = table.columns.stream().map(column -> "x." + column)
                    .collect(java.util.stream.Collectors.joining(","));
            try (var statement = normalized.createStatement(); var rows = statement.executeQuery(
                    "SELECT p.legacy_id," + columns + " FROM " + table.name
                            + " x JOIN periodic_invoice p ON p.id=x.periodic_invoice_id")) {
                while (rows.next()) {
                    List<Object> values = new ArrayList<>();
                    for (int index = 0; index < table.columns.size(); index++) {
                        String column = table.columns.get(index);
                        Object value = switch (column) {
                            case "voucher_date" -> rows.getObject(index + 2, LocalDate.class);
                            case "edited_at" -> rows.getObject(index + 2, OffsetDateTime.class);
                            default -> rows.getObject(index + 2);
                        };
                        values.add(value);
                    }
                    entries.add(new Entry(table, rows.getInt(1), values));
                }
            }
        }
        return entries;
    }

    /** Old V19 databases remain readable by existing CLI paths, but cannot claim complete periodic preservation. */
    static boolean hasSchema(Connection connection) throws SQLException {
        int present = 0;
        for (Table table : Table.values()) {
            try (var rows = connection.getMetaData().getTables(null, "PUBLIC",
                    table.name.toUpperCase(java.util.Locale.ROOT), new String[]{"TABLE"})) {
                if (rows.next()) present++;
            }
        }
        if (present != 0 && present != Table.values().length) throw recoveryRequired("partial V20 snapshot schema");
        return present != 0;
    }

    private static void requireSchema(Connection connection) throws SQLException {
        if (!hasSchema(connection)) throw recoveryRequired("V20 snapshot schema is absent");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM bokfri_schema_history WHERE version=20")) {
            rows.next();
            if (rows.getInt(1) != 1) throw recoveryRequired("V20 migration history is absent");
        }
    }

    private static void validateCoverage(Connection connection) throws SQLException {
        reject(connection, "SELECT COUNT(*) FROM periodic_invoice p LEFT JOIN periodic_invoice_snapshot s"
                + " ON s.periodic_invoice_id=p.id AND s.invoice_index=-1 WHERE s.periodic_invoice_id IS NULL");
        reject(connection, "SELECT COUNT(*) FROM periodic_invoice_instance i LEFT JOIN periodic_invoice_snapshot s"
                + " ON s.periodic_invoice_id=i.periodic_invoice_id AND s.invoice_index=i.row_number"
                + " WHERE s.periodic_invoice_id IS NULL OR s.present=FALSE");
        reject(connection, "SELECT COUNT(*) FROM periodic_invoice_snapshot s LEFT JOIN periodic_invoice_instance i"
                + " ON i.periodic_invoice_id=s.periodic_invoice_id AND i.row_number=s.invoice_index"
                + " WHERE s.invoice_index>=0 AND i.periodic_invoice_id IS NULL");
        for (Table table : List.of(Table.ADDRESS, Table.ACCOUNT, Table.VOUCHER, Table.ROW)) {
            reject(connection, "SELECT COUNT(*) FROM " + table.name + " x JOIN periodic_invoice_snapshot s"
                    + " ON s.periodic_invoice_id=x.periodic_invoice_id AND s.invoice_index=x.invoice_index"
                    + " WHERE s.present=FALSE");
        }
        reject(connection, "SELECT COUNT(*) FROM (SELECT periodic_invoice_id,invoice_number"
                + " FROM periodic_invoice_added_state GROUP BY periodic_invoice_id,invoice_number"
                + " HAVING COUNT(*)>1) x");
    }

    private static void reject(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            if (rows.getLong(1) != 0) throw recoveryRequired("incomplete or inconsistent periodic snapshot markers");
        }
    }

    private static SQLException recoveryRequired(String detail) {
        return new SQLException("PERIODIC_SNAPSHOT_RECOVERY_REQUIRED: " + detail
                + "; do not infer missing snapshots from current registers"
                + " or automatically backfill older projections");
    }

    static SemanticFingerprint fingerprint(List<Entry> entries) {
        SemanticFingerprintBuilder builder = new SemanticFingerprintBuilder();
        for (Table table : Table.values()) {
            var domain = builder.domain("periodic-nested-" + table.name);
            List<String> hashes = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.table() != table) continue;
                CanonicalFingerprintWriter row = new CanonicalFingerprintWriter();
                row.writeInteger(entry.legacyId());
                for (Object value : entry.values()) row.writeString(canonical(value));
                hashes.add(row.finish());
            }
            hashes.sort(String::compareTo);
            for (String hash : hashes) domain.startRecord(hash).writeString(hash);
            domain.finish();
        }
        return builder.finish();
    }

    private static String canonical(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return CanonicalValues.amount(decimal).toPlainString();
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        return value.toString();
    }

    static SemanticFingerprint combine(SemanticFingerprint base, SemanticFingerprint nested) {
        Map<String, SemanticFingerprint.DomainFingerprint> domains = new TreeMap<>(base.domains());
        nested.domains().forEach((name, value) -> {
            if (domains.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("Duplicate domain " + name);
            }
        });
        CanonicalFingerprintWriter aggregate = new CanonicalFingerprintWriter();
        domains.forEach((name, domain) -> aggregate.writeBoundary(name).writeString(domain.sha256())
                .writeLong(domain.recordCount()).writeString(domain.debitTotal()).writeString(domain.creditTotal()));
        return new SemanticFingerprint(aggregate.finish(), domains);
    }

    static void deleteCompanySnapshots(Connection connection, long companyId) throws SQLException {
        if (!hasSchema(connection)) return; // Historical V19 catalogs have no extension graph to delete.
        for (Table table : List.of(Table.ROW, Table.VOUCHER, Table.ACCOUNT,
                Table.ADDRESS, Table.ADDED, Table.SNAPSHOT)) {
            try (var statement = connection.prepareStatement("DELETE FROM " + table.name
                    + " WHERE periodic_invoice_id IN (SELECT id FROM periodic_invoice WHERE company_id=?)")) {
                statement.setLong(1, companyId);
                statement.executeUpdate();
            }
        }
    }
}
