package org.fribok.bookkeeping.service.creditinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import se.swedsoft.bookkeeping.calc.math.SSSaleMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.SSStandardText;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Creates unbooked credit corrections for booked customer invoices without rewriting the original
 * financial graph or ledger. Caller owns connection/commit and exclusive application access.
 */
public final class NormalizedCustomerCreditService {
    private final Connection connection;
    private final Clock clock;

    /** Uses only the explicit normalized connection and clock; never consults legacy globals. */
    public NormalizedCustomerCreditService(Connection connection, Clock clock) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Opaque source/balance/number token; detached presentation edits are ignored when creating. */
    public static final class Plan {
        private final int company, year;
        private final long sourceId;
        private final LocalDate date;
        private final BigDecimal requestedAmount;
        private final SSCreditInvoice credit;
        private final BigDecimal remainingBalance;
        private final String signature;

        private Plan(int company, int year, long sourceId, LocalDate date, BigDecimal requestedAmount,
                SSCreditInvoice credit, BigDecimal remainingBalance, String signature) {
            this.company = company; this.year = year; this.sourceId = sourceId; this.date = date;
            this.requestedAmount = requestedAmount; this.credit = credit;
            this.remainingBalance = remainingBalance; this.signature = signature;
        }
        /** Company migration identity bound to the request. */
        public int companyId() { return company; }
        /** Year migration identity bound to the request. */
        public int yearId() { return year; }
        /** Detached presentation snapshot, not trusted as creation input. */
        public SSCreditInvoice credit() { return credit; }
        /** Outstanding balance at the existing two-decimal invoice-balance precision. */
        public BigDecimal remainingBalance() { return remainingBalance; }
    }

    /** Persisted credit and its stable identities; the outer transaction is not committed. */
    public record Created(long id, int legacyId, SSCreditInvoice credit) { }

    /**
     * Plans a correction by stable original identity. All credits (booked or unbooked) and
     * inpayments reserve the existing balance. An omitted amount means the remaining balance,
     * not the original full total. Uses the established proportional scale-12 price algorithm.
     */
    public Plan plan(int companyId, int yearId, long invoiceId, LocalDate date, BigDecimal amount)
            throws SQLException {
        NormalizedAccountingStore accounting = new NormalizedAccountingStore(connection, clock);
        var company = accounting.getCompanies().stream().filter(c -> c.getId() == companyId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Company not found"));
        accounting.setCurrentCompany(company);
        var year = accounting.getYears().stream().filter(y -> y.getId() == yearId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Accounting year does not belong to company"));
        NormalizedInvoiceStore invoices = new NormalizedInvoiceStore(connection);
        var source = invoices.findInvoice(companyId, invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Original invoice not found in company"));
        SSInvoice original = source.document();
        if (!original.isEntered()) throw new IllegalArgumentException("Original invoice must be entered before crediting");
        if (original.getNumber() == null || invoices.listInvoices(companyId).stream()
                .filter(item -> Objects.equals(item.document().getNumber(), original.getNumber())).count() != 1) {
            throw new IllegalArgumentException("Original invoice number is missing or ambiguous");
        }
        if (original.getType() == SSInvoiceType.CASH) throw new IllegalArgumentException("Cash invoice has no credit balance");
        boolean rounding = company.isRoundingOff();
        BigDecimal total = SSSaleMath.getTotalSum(original, rounding);
        BigDecimal balance = total
                .subtract(invoices.creditInvoiceSum(companyId, original.getNumber(), rounding))
                .subtract(invoices.inpaymentSum(companyId, original.getNumber()))
                .setScale(2, RoundingMode.HALF_UP);
        if (total.signum() <= 0 || balance.signum() <= 0) {
            throw new IllegalArgumentException("Original invoice has no remaining balance to credit");
        }
        BigDecimal target = amount == null ? balance : amount;
        if (target.signum() <= 0 || target.compareTo(balance) > 0) {
            throw new IllegalArgumentException("Credit amount must be positive and not exceed invoice balance");
        }
        int maximum = invoices.getCreditInvoices(companyId).stream().map(SSCreditInvoice::getNumber)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0);
        int floor = new NormalizedRegisterStore(connection).autoIncrementValue(companyId, "creditinvoice");
        int previous = Math.max(0, Math.max(maximum, floor));
        if (previous == Integer.MAX_VALUE) throw new IllegalArgumentException("Credit invoice number exhausted");

        // Separate new-document construction from historical snapshot hydration. No hidden clock or voucher.
        SSCreditInvoice credit = SSCreditInvoice.forPersistence();
        credit.copyFrom(original);
        credit.setCreditingNr(original.getNumber()); credit.setNumber(previous + 1);
        credit.setLocalDate(date == null ? LocalDate.now(clock.withZone(ZoneId.of("Europe/Stockholm"))) : date);
        credit.setEntered(false); credit.setPrinted(false); credit.setVoucher(null);
        credit.setText(company.getStandardText(SSStandardText.Creditinvoice).orElse(null));
        if (target.compareTo(total) != 0) {
            BigDecimal factor = target.divide(total, 12, RoundingMode.HALF_UP);
            for (var row : credit.getRows()) {
                if (row.getUnitprice() != null) row.setUnitprice(row.getUnitprice().multiply(factor));
            }
        }
        BigDecimal actual = money(SSSaleMath.getTotalSum(credit, rounding));
        if (actual.signum() <= 0 || actual.compareTo(balance) > 0 || actual.compareTo(money(target)) != 0) {
            throw new IllegalArgumentException("Requested credit cannot be represented under invoice rounding rules");
        }
        // Validate an accounting preview on a detached copy, never persist a speculative booked voucher.
        SSAccountPlan accounts = new SSAccountPlan(); accounts.setAccounts(accounting.getAccounts(year));
        SSCreditInvoice checking = SSCreditInvoice.forPersistence(); checking.copyFrom(credit);
        for (var row : checking.getRows()) {
            if (row.getSum().orElse(BigDecimal.ZERO).signum() != 0
                    && (row.getAccountNr() == null || accounts.getAccount(row.getAccountNr()) == null)) {
                throw new IllegalArgumentException("Missing sales account in credit invoice");
            }
        }
        SSVoucher voucher = checking.generateVoucher(accounts, rounding,
                new NormalizedRegisterStore(connection).getProjects(companyId),
                new NormalizedRegisterStore(connection).getResultUnits(companyId));
        if (SSVoucherMath.getDebetSum(voucher, accounts.getAccounts())
                .compareTo(SSVoucherMath.getCreditSum(voucher, accounts.getAccounts())) != 0) {
            throw new IllegalArgumentException("Credit invoice would create an unbalanced journal");
        }
        CanonicalFingerprintWriter signature = new CanonicalFingerprintWriter();
        signature.writeInteger(companyId).writeInteger(yearId).writeString(Long.toString(invoiceId))
                .writeInteger(credit.getNumber()).writeDate(credit.getLocalDate()).writeDecimal(target)
                .writeDecimal(balance).writeBoolean(rounding).writeString(credit.getText());
        sourceSignature(signature, invoiceId);
        for (var row : voucher.getRows()) {
            signature.writeBoundary("accounting-row").writeInteger(row.getAccountNr())
                    .writeString(row.getProjectNr()).writeString(row.getResultUnitNr())
                    .writeDecimal(row.getDebet()).writeDecimal(row.getCredit());
        }
        return new Plan(companyId, yearId, invoiceId, credit.getLocalDate(), amount, credit, balance, signature.finish());
    }

    /**
     * Revalidates the complete source graph, balance and next number, then inserts a fresh credit
     * under one savepoint. Configured counters remain numbering floors, as in legacy insertion;
     * no ledger posting or counter bump is implicit. Caller still owns the final commit.
     */
    public Created create(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) throw new SQLException("Credit creation requires a caller-owned transaction");
        Savepoint savepoint = connection.setSavepoint();
        try {
            Plan fresh = plan(expected.company, expected.year, expected.sourceId, expected.date, expected.requestedAmount);
            if (!fresh.signature.equals(expected.signature)) {
                throw new IllegalArgumentException("Credit plan is stale; preview it again");
            }
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(connection);
            long id = store.addCreditInvoice(fresh.company, fresh.credit);
            var stored = store.findCreditInvoice(fresh.company, id).orElseThrow();
            boolean rounding = new NormalizedAccountingStore(connection, clock).getCompanies().stream()
                    .filter(c -> c.getId() == fresh.company).findFirst().orElseThrow().isRoundingOff();
            if (money(SSSaleMath.getTotalSum(stored.document(), rounding))
                    .compareTo(money(SSSaleMath.getTotalSum(fresh.credit, rounding))) != 0) {
                throw new SQLException("Stored credit changed monetary value during SQL conversion");
            }
            connection.releaseSavepoint(savepoint);
            return new Created(id, stored.legacyId(), stored.document());
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(savepoint); }
            catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2, RoundingMode.HALF_UP); }

    private void sourceSignature(CanonicalFingerprintWriter writer, long invoiceId) throws SQLException {
        querySignature(writer, "customer_invoice", "id", "id", invoiceId);
        querySignature(writer, "customer_invoice_address", "invoice_id", "address_type", invoiceId);
        querySignature(writer, "customer_invoice_default_account", "invoice_id", "account_type", invoiceId);
        querySignature(writer, "customer_invoice_row", "invoice_id", "row_number", invoiceId);
        querySignature(writer, "customer_invoice_voucher", "invoice_id", "invoice_id", invoiceId);
        querySignature(writer, "customer_invoice_voucher_row", "invoice_id", "row_number", invoiceId);
    }

    /** Ephemeral local token over known typed SQL values, never Java-object serialization. */
    private void querySignature(CanonicalFingerprintWriter writer, String table, String owner, String order, long id)
            throws SQLException {
        writer.writeBoundary(table);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + table + " WHERE " + owner + "=? ORDER BY " + order)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                var metadata = result.getMetaData();
                while (result.next()) {
                    writer.writeBoundary("row");
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        int type = metadata.getColumnType(i);
                        if (!List.of(Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT,
                                Types.DECIMAL, Types.NUMERIC, Types.BOOLEAN, Types.BIT,
                                Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.DATE,
                                Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE).contains(type)) {
                            throw new SQLException("Unsupported credit source SQL type " + type);
                        }
                        writer.writeString(metadata.getColumnName(i)).writeInteger(type).writeString(result.getString(i));
                    }
                }
            }
        }
    }
}
