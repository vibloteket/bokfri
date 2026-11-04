package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSProductRow;
import se.swedsoft.bookkeeping.data.SSStock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only all-time stock projection for register exports, without partially hydrated editable documents. */
public final class NormalizedStockStore {
    private final Connection connection;

    /** Uses the caller-owned normalized connection; never commits or closes it. */
    public NormalizedStockStore(Connection connection) { this.connection = connection; }

    /** Preserves the legacy all-time stock flags, unlinked-order rules and one-level parcel expansion. */
    public SSStock read(int company, List<SSProduct> products) throws SQLException {
        Map<String, SSProduct> register = new HashMap<>();
        for (SSProduct product : products) register.putIfAbsent(product.getNumber(), product);
        Map<String, Integer> quantity = new HashMap<>();
        Map<String, Integer> reserved = new HashMap<>();
        Map<String, Integer> ordered = new HashMap<>();
        sales(company, "customer_order", "order_id", "invoice_number IS NULL", false, 1, register, reserved);
        sales(company, "customer_invoice", "invoice_id", "stock_influencing=TRUE", true, -1, register, quantity);
        sales(company, "customer_credit_invoice", "credit_invoice_id", "stock_influencing=TRUE", true, 1,
                register, quantity);
        sales(company, "purchase_order", "purchase_order_id", "invoice_number IS NULL AND stock_influencing=TRUE",
                false, 1, register, ordered);
        sales(company, "supplier_invoice", "supplier_invoice_id", "stock_influencing=TRUE", false, 1,
                register, quantity);
        sales(company, "supplier_credit_invoice", "supplier_credit_invoice_id", "stock_influencing=TRUE", false, -1,
                register, quantity);
        changes(company, "inventory", "inventory_id", 1, quantity);
        changes(company, "indelivery", "indelivery_id", 1, quantity);
        changes(company, "outdelivery", "outdelivery_id", -1, quantity);
        return new SSStock(products, quantity, reserved, ordered);
    }

    private void sales(int company, String table, String parent, String filter, boolean stockOnly, int sign,
            Map<String, SSProduct> products, Map<String, Integer> counts) throws SQLException {
        query(company, table, parent, "quantity", filter, (number, value) -> {
            SSProduct product = products.get(number);
            if (stockOnly && product == null) return;
            if (product != null && product.isParcel()) {
                for (SSProductRow row : product.getParcelRows()) {
                    counts.merge(row.getProductNr(), sign * row.getQuantity() * value.intValueExact(), Integer::sum);
                }
            } else if (!stockOnly || product.isStockProduct()) {
                counts.merge(number, sign * value.intValueExact(), Integer::sum);
            }
        });
    }

    private void changes(int company, String table, String parent, int sign, Map<String, Integer> counts)
            throws SQLException {
        query(company, table, parent, "change_quantity", "TRUE", (number, value) ->
                counts.merge(number, sign * value.intValueExact(), Integer::sum));
    }

    private void query(int company, String table, String parent, String amount, String filter, StockRow consumer)
            throws SQLException {
        String sql = "SELECT r.product_number,r." + amount + " FROM " + table + " d JOIN company c ON c.id=d.company_id"
                + " JOIN " + table + "_row r ON r." + parent + "=d.id WHERE c.legacy_id=? AND (" + filter + ")"
                + " ORDER BY d.legacy_id,r.row_number";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, company);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    BigDecimal value = rows.getBigDecimal(2);
                    if (value != null) consumer.accept(rows.getString(1), value);
                }
            }
        }
    }

    @FunctionalInterface
    private interface StockRow { void accept(String number, BigDecimal value); }
}
