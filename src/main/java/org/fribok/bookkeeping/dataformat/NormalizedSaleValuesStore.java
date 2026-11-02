package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.service.SaleValuesData;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Narrow read-only sales-values projection; does not hydrate partial editable orders or tenders. */
public final class NormalizedSaleValuesStore {
    private final Connection connection;

    /** Uses the caller-owned normalized connection without committing or closing it. */
    public NormalizedSaleValuesStore(Connection connection) {
        this.connection = connection;
    }

    /** Reads only the selected company's stored sale dates, rates and discounted row amounts. */
    public SaleValuesData read(int companyLegacyId) throws SQLException {
        return new SaleValuesData(rows(companyLegacyId, "tender", "tender_id"),
                rows(companyLegacyId, "customer_order", "order_id"),
                rows(companyLegacyId, "customer_invoice", "invoice_id"),
                rows(companyLegacyId, "customer_credit_invoice", "credit_invoice_id"));
    }

    private List<SaleValuesData.Row> rows(int company, String table, String parent) throws SQLException {
        List<SaleValuesData.Row> result = new ArrayList<>();
        String date = table.startsWith("customer_") && !table.equals("customer_order")
                ? "invoice_date" : "sale_date";
        String sql = "SELECT d." + date + ",d.currency_rate,r.unit_price,r.quantity,r.discount FROM "
                + table + " d JOIN company c ON c.id=d.company_id JOIN " + table
                + "_row r ON r." + parent + "=d.id AND r.company_id=d.company_id"
                + " WHERE c.legacy_id=? ORDER BY d.legacy_id,r.row_number";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, company);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SSSaleRow row = new SSSaleRow();
                    row.setUnitprice(rows.getBigDecimal(3));
                    row.setQuantity(rows.getBigDecimal(4));
                    row.setDiscount(rows.getBigDecimal(5));
                    result.add(new SaleValuesData.Row(rows.getObject(1, LocalDate.class),
                            row.getSum().orElse(null), rows.getBigDecimal(2)));
                }
            }
        }
        return result;
    }
}
