package org.fribok.bookkeeping.dataformat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Deletes the explicit non-core company graph; identifiers below are fixed schema names. */
final class NormalizedCompanyDeletion {
    private NormalizedCompanyDeletion() {}

    static void deleteDocumentsAndRegisters(Connection connection, long companyId) throws SQLException {
        document(connection, companyId, "own_report", "own_report_id",
                "account_budget", "account_row", "heading");
        document(connection, companyId, "periodic_invoice", "periodic_invoice_id",
                "instance_row", "instance", "template_row");
        document(connection, companyId, "customer_invoice", "invoice_id",
                "voucher_row", "voucher", "row", "default_account", "address");
        document(connection, companyId, "customer_credit_invoice", "credit_invoice_id",
                "voucher_row", "voucher", "row", "default_account", "address");
        document(connection, companyId, "supplier_invoice", "supplier_invoice_id",
                "voucher_row", "voucher", "row", "default_account");
        document(connection, companyId, "supplier_credit_invoice", "supplier_credit_invoice_id",
                "voucher_row", "voucher", "row", "default_account");
        for (String payment : List.of("inpayment", "outpayment")) {
            document(connection, companyId, payment, payment + "_id",
                    "voucher_row", "voucher", "row", "default_account");
        }
        document(connection, companyId, "customer_order", "order_id", "row", "default_account", "address");
        document(connection, companyId, "tender", "tender_id", "row", "default_account", "address");
        document(connection, companyId, "purchase_order", "purchase_order_id", "row", "default_account", "address");
        for (String stock : List.of("indelivery", "outdelivery", "inventory")) {
            document(connection, companyId, stock, stock + "_id", "row");
        }
        document(connection, companyId, "product", "product_id", "component", "description", "default_account");
        document(connection, companyId, "customer", "customer_id", "address");
        document(connection, companyId, "supplier", "supplier_id", "address");
        for (String table : List.of("auto_distribution_row", "auto_distribution",
                "voucher_template_row", "voucher_template")) {
            delete(connection, "DELETE FROM " + table + " WHERE company_id=?", companyId);
        }
    }

    static void deleteDimensions(Connection connection, long companyId) throws SQLException {
        for (String table : List.of("project", "result_unit")) {
            delete(connection, "DELETE FROM " + table + " WHERE company_id=?", companyId);
        }
    }

    private static void document(Connection connection, long companyId, String parent,
            String parentColumn, String... children) throws SQLException {
        for (String child : children) {
            delete(connection, "DELETE FROM " + parent + "_" + child + " WHERE " + parentColumn
                    + " IN (SELECT id FROM " + parent + " WHERE company_id=?)", companyId);
        }
        delete(connection, "DELETE FROM " + parent + " WHERE company_id=?", companyId);
    }

    private static void delete(Connection connection, String sql, long companyId) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, companyId);
            statement.executeUpdate();
        }
    }
}
