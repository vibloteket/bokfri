package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SSDB-shaped reads/writes over the normalized inpayment tables, including rows,
 * default accounts, and the main and difference voucher snapshots.
 */
public final class NormalizedInpaymentStore {
    private final Connection connection;
    private final NormalizedRegisterStore registers;

    public NormalizedInpaymentStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.registers = new NormalizedRegisterStore(connection);
    }

    /**
     * Returns the number the next created inpayment receives: one above the greater of the
     * highest existing number and the company's "inpayment" auto-increment counter, mirroring
     * the legacy numbering contract.
     */
    public int nextInpaymentNumber(int companyLegacyId) throws SQLException {
        int highest = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(number),0) FROM inpayment "
                        + "WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                highest = result.getInt(1);
            }
        }
        int counter = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT counter_value FROM company_auto_increment WHERE counter_name='inpayment' "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    counter = result.getInt(1);
                }
            }
        }
        return Math.max(highest, counter) + 1;
    }

    public List<SSInpayment> getInpayments(int companyLegacyId) throws SQLException {
        Map<String, SSCurrency> currencies = new HashMap<>();
        for (SSCurrency currency : registers.getCurrencies()) {
            currencies.put(currency.getName(), currency);
        }
        Map<Long, SSInpayment> byId = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,number,payment_date,text,entered FROM inpayment "
                        + "WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY number")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSInpayment inpayment = new SSInpayment();
                    int number = result.getInt(2);
                    inpayment.setNumber(result.wasNull() ? null : number);
                    inpayment.setLocalDate(result.getObject(3, LocalDate.class));
                    inpayment.setText(result.getString(4));
                    inpayment.setEntered(result.getBoolean(5));
                    inpayment.getDefaultAccounts();
                    byId.put(result.getLong(1), inpayment);
                }
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        String idFilter = "SELECT id FROM inpayment WHERE company_id="
                + "(SELECT id FROM company WHERE legacy_id=" + companyLegacyId + ")";
        readRows(idFilter, byId, currencies);
        readDefaultAccounts(idFilter, byId);
        readVouchers(idFilter, byId);
        return new ArrayList<>(byId.values());
    }

    public void addInpayment(int companyLegacyId, SSInpayment inpayment) throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO inpayment (legacy_id,company_id,number,payment_date,text,entered) "
                        + "SELECT ?,id,?,?,?,? FROM company WHERE legacy_id=?",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, nextLegacyId());
            if (inpayment.getNumber() == null) {
                statement.setNull(2, java.sql.Types.INTEGER);
            } else {
                statement.setInt(2, inpayment.getNumber());
            }
            if (inpayment.getLocalDate() == null) {
                statement.setNull(3, java.sql.Types.DATE);
            } else {
                statement.setObject(3, inpayment.getLocalDate());
            }
            statement.setString(4, inpayment.getText());
            statement.setBoolean(5, inpayment.isEntered());
            statement.setInt(6, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key returned for inpayment");
                }
                id = keys.getLong(1);
            }
        }
        int rowNumber = 0;
        for (SSInpaymentRow row : inpayment.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inpayment_row (inpayment_id,row_number,invoice_number,"
                            + "invoice_currency_code,invoice_currency_rate,value,currency_rate) "
                            + "VALUES (?,?,?,?,?,?,?)")) {
                statement.setLong(1, id);
                statement.setInt(2, rowNumber++);
                if (row.getInvoiceNr() == null) {
                    statement.setNull(3, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(3, row.getInvoiceNr());
                }
                statement.setString(4, row.getInvoiceCurrency() == null
                        ? null : row.getInvoiceCurrency().getName());
                statement.setBigDecimal(5, row.getInvoiceCurrencyRate());
                statement.setBigDecimal(6, row.getValue());
                statement.setBigDecimal(7, row.getCurrencyRate());
                statement.executeUpdate();
            }
        }
        for (Map.Entry<SSDefaultAccount, Integer> account
                : inpayment.getDefaultAccounts().entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inpayment_default_account (inpayment_id,account_type,"
                            + "account_number) VALUES (?,?,?)")) {
                statement.setLong(1, id);
                statement.setString(2, account.getKey().name());
                if (account.getValue() == null) {
                    statement.setNull(3, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(3, account.getValue());
                }
                statement.executeUpdate();
            }
        }
        insertVoucher(id, "main", inpayment.getVoucher());
        insertVoucher(id, "difference", inpayment.getStoredDifference());
    }

    // ------------------------------------------------------------------ internals

    private void readRows(String idFilter, Map<Long, SSInpayment> byId,
            Map<String, SSCurrency> currencies) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT inpayment_id,invoice_number,invoice_currency_code,invoice_currency_rate,"
                        + "value,currency_rate FROM inpayment_row WHERE inpayment_id IN ("
                        + idFilter + ") ORDER BY inpayment_id,row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSInpayment inpayment = byId.get(result.getLong(1));
                    if (inpayment == null) {
                        continue;
                    }
                    SSInpaymentRow row = new SSInpaymentRow();
                    int invoiceNumber = result.getInt(2);
                    row.setInvoiceNr(result.wasNull() ? null : invoiceNumber);
                    row.setInvoiceCurrency(currencies.get(result.getString(3)));
                    row.setInvoiceCurrencyRate(result.getBigDecimal(4));
                    row.setValue(result.getBigDecimal(5));
                    row.setCurrencyRate(result.getBigDecimal(6));
                    inpayment.getRows().add(row);
                }
            }
        }
    }

    private void readDefaultAccounts(String idFilter, Map<Long, SSInpayment> byId)
            throws SQLException {
        Map<Long, Map<SSDefaultAccount, Integer>> accounts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT inpayment_id,account_type,account_number FROM inpayment_default_account "
                        + "WHERE inpayment_id IN (" + idFilter
                        + ") ORDER BY inpayment_id,account_type")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int number = result.getInt(3);
                    accounts.computeIfAbsent(result.getLong(1), key -> new HashMap<>())
                            .put(SSDefaultAccount.valueOf(result.getString(2)),
                                    result.wasNull() ? null : number);
                }
            }
        }
        accounts.forEach((id, map) -> {
            SSInpayment inpayment = byId.get(id);
            if (inpayment != null) {
                inpayment.setDefaultAccounts(map);
            }
        });
    }

    private void readVouchers(String idFilter, Map<Long, SSInpayment> byId) throws SQLException {
        record VoucherKey(long inpaymentId, String kind) {}
        Map<VoucherKey, SSVoucher> vouchers = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT inpayment_id,voucher_kind,number,voucher_date,description "
                        + "FROM inpayment_voucher WHERE inpayment_id IN (" + idFilter
                        + ") ORDER BY inpayment_id,voucher_kind")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = new SSVoucher(result.getInt(3));
                    voucher.setLocalDate(result.getObject(4, LocalDate.class));
                    voucher.setDescription(result.getString(5));
                    vouchers.put(new VoucherKey(result.getLong(1), result.getString(2)), voucher);
                }
            }
        }
        if (vouchers.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT inpayment_id,voucher_kind,account_number,project_number,"
                        + "result_unit_number,debit,credit,edited_at,edited_signature,crossed,"
                        + "added FROM inpayment_voucher_row WHERE inpayment_id IN (" + idFilter
                        + ") ORDER BY inpayment_id,voucher_kind,row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = vouchers.get(
                            new VoucherKey(result.getLong(1), result.getString(2)));
                    if (voucher != null) {
                        voucher.getRows().add(NormalizedInvoiceStore.voucherRowFrom(result, 3));
                    }
                }
            }
        }
        vouchers.forEach((key, voucher) -> {
            SSInpayment inpayment = byId.get(key.inpaymentId());
            if (inpayment == null) {
                return;
            }
            if ("main".equals(key.kind())) {
                inpayment.setVoucher(voucher);
            } else {
                inpayment.setDifference(voucher);
            }
        });
    }

    private void insertVoucher(long inpaymentId, String kind, SSVoucher voucher)
            throws SQLException {
        if (voucher == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO inpayment_voucher (inpayment_id,voucher_kind,number,voucher_date,"
                        + "description) VALUES (?,?,?,?,?)")) {
            statement.setLong(1, inpaymentId);
            statement.setString(2, kind);
            statement.setInt(3, voucher.getNumber());
            if (voucher.getLocalDate() == null) {
                statement.setNull(4, java.sql.Types.DATE);
            } else {
                statement.setObject(4, voucher.getLocalDate());
            }
            statement.setString(5, voucher.getDescription());
            statement.executeUpdate();
        }
        int rowNumber = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inpayment_voucher_row (inpayment_id,voucher_kind,row_number,"
                            + "account_number,project_number,result_unit_number,debit,credit,"
                            + "edited_at,edited_signature,crossed,added) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, inpaymentId);
                statement.setString(2, kind);
                statement.setInt(3, rowNumber++);
                NormalizedInvoiceStore.bindVoucherRow(statement, 4, row);
                statement.executeUpdate();
            }
        }
    }

    private int nextLegacyId() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(legacy_id),0)+1 FROM inpayment")) {
            result.next();
            return result.getInt(1);
        }
    }
}
