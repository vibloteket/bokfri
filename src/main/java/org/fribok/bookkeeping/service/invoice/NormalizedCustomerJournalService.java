package org.fribok.bookkeeping.service.invoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import org.fribok.bookkeeping.service.creditinvoice.CreditInvoiceJournalPlan;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit normalized customer invoice/credit journal planning and atomic posting.
 * Owns only a local savepoint: the caller owns the connection, outer transaction and commit.
 * Requires exclusive application ownership; this is not a concurrent number allocator.
 */
public final class NormalizedCustomerJournalService {
    private final Connection connection;
    private final Clock clock;

    /** Uses the supplied connection and clock, without consulting the legacy singleton. */
    public NormalizedCustomerJournalService(Connection connection, Clock clock) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Opaque posting token with detached presentation snapshots. Posting ignores mutations
     * to those snapshots and re-reads/regenerates the selected documents and accounting context.
     */
    public static final class Plan {
        private final boolean credit;
        private final int company, year, journalNumber, voucherNumber;
        private final LocalDate from, to;
        private final List<NormalizedInvoiceStore.Stored<? extends SSInvoice>> documents;
        private final SSVoucher voucher;
        private final String signature;

        private Plan(boolean credit, int company, int year, int journalNumber, int voucherNumber,
                LocalDate from, LocalDate to,
                List<NormalizedInvoiceStore.Stored<? extends SSInvoice>> documents,
                SSVoucher voucher, String signature) {
            this.credit = credit; this.company = company; this.year = year;
            this.journalNumber = journalNumber; this.voucherNumber = voucherNumber;
            this.from = from; this.to = to; this.documents = List.copyOf(documents);
            this.voucher = voucher; this.signature = signature;
        }

        /** Company migration identity bound to this plan. */
        public int companyId() { return company; }
        /** Accounting-year migration identity bound to this plan. */
        public int yearId() { return year; }
        /** Invoice-only view for existing presentation/PDF code. */
        public InvoiceJournalPlan invoicePlan() {
            if (credit) throw new IllegalStateException("Credit journal has no invoice-only view");
            return new InvoiceJournalPlan(journalNumber, from, to,
                    documents.stream().map(item -> (SSInvoice) item.document()).toList(), voucher);
        }
        /** Credit-only view for existing presentation/PDF code. */
        public CreditInvoiceJournalPlan creditPlan() {
            if (!credit) throw new IllegalStateException("Invoice journal has no credit-only view");
            return new CreditInvoiceJournalPlan(journalNumber, from, to,
                    documents.stream().map(item -> (SSCreditInvoice) item.document()).toList(), voucher);
        }
    }

    /** Empty selection is distinct from invalid/stale accounting inputs for CLI compatibility. */
    public static final class EmptyJournalException extends IllegalArgumentException {
        private EmptyJournalException() { super("No unbooked documents in selected period"); }
    }

    /** Posted identities/numbers; the caller must still commit the outer transaction. */
    public record Posted(int journalNumber, int voucherNumber, List<Long> documentIds) {
        public Posted { documentIds = List.copyOf(documentIds); }
    }

    /** Plans unbooked customer invoices in the inclusive period; performs no SQL writes. */
    public Plan planInvoices(int company, int year, LocalDate from, LocalDate to) throws SQLException {
        return plan(false, company, year, from, to);
    }

    /** Plans unbooked customer credits in the inclusive period; performs no SQL writes. */
    public Plan planCredits(int company, int year, LocalDate from, LocalDate to) throws SQLException {
        return plan(true, company, year, from, to);
    }

    private Plan plan(boolean credit, int companyId, int yearId, LocalDate from, LocalDate to)
            throws SQLException {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Journal period is invalid");
        }
        NormalizedAccountingStore accounting = new NormalizedAccountingStore(connection, clock);
        SSNewCompany company = accounting.getCompanies().stream().filter(c -> c.getId() == companyId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Company not found"));
        accounting.setCurrentCompany(company);
        SSNewAccountingYear year = accounting.getYears().stream()
                .filter(y -> y.getId() == yearId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Accounting year does not belong to company"));
        NormalizedRegisterStore registers = new NormalizedRegisterStore(connection);
        NormalizedInvoiceStore invoices = new NormalizedInvoiceStore(connection);
        List<NormalizedInvoiceStore.Stored<? extends SSInvoice>> all = new ArrayList<>();
        if (credit) all.addAll(invoices.listCreditInvoices(companyId));
        else all.addAll(invoices.listInvoices(companyId));
        Map<Integer, Integer> numberCounts = new HashMap<>();
        for (var item : all) numberCounts.merge(item.document().getNumber(), 1, Integer::sum);
        List<NormalizedInvoiceStore.Stored<? extends SSInvoice>> selected = all.stream()
                .filter(item -> !item.document().isEntered())
                .filter(item -> {
                    LocalDate date = item.document().getLocalDate();
                    // Preserve existing CLI selection: undated invoices included, undated credits excluded.
                    return date == null ? !credit : !date.isBefore(from) && !date.isAfter(to);
                })
                .sorted(Comparator.comparing(item -> item.document().getNumber(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (selected.isEmpty()) throw new EmptyJournalException();
        String counter = credit ? "creditinvoicejournal" : "invoicejournal";
        int journalNumber = next(registers.autoIncrementValue(companyId, counter), "Journal number");
        int voucherNumber = next(accounting.getVouchers(year).stream()
                .mapToInt(SSVoucher::getNumber).max().orElse(0), "Voucher number");
        SSAccountPlan accountPlan = new SSAccountPlan(); accountPlan.setAccounts(accounting.getAccounts(year));
        var projects = registers.getProjects(companyId);
        var resultUnits = registers.getResultUnits(companyId);
        SSVoucher journal = new SSVoucher(0, to);
        journal.setDescription((credit ? "Kreditfakturajournal nr " : "Fakturajournal nr ") + journalNumber);
        CanonicalFingerprintWriter signature = new CanonicalFingerprintWriter();
        signature.writeBoolean(credit).writeInteger(companyId).writeInteger(yearId)
                .writeInteger(journalNumber).writeInteger(voucherNumber).writeDate(from).writeDate(to);
        for (var item : selected) {
            SSInvoice invoice = item.document();
            if (invoice.getNumber() == null || numberCounts.get(invoice.getNumber()) != 1) {
                throw new IllegalArgumentException("Missing or ambiguous document number for SQL identity " + item.id());
            }
            for (var row : invoice.getRows()) {
                if (row.getSum().orElse(java.math.BigDecimal.ZERO).signum() != 0
                        && (row.getAccountNr() == null || accountPlan.getAccount(row.getAccountNr()) == null)) {
                    throw new IllegalArgumentException("Missing sales account for document " + invoice.getNumber());
                }
            }
            // Use business NORMAL fallback without rewriting the persisted nullable header.
            invoice.getType();
            SSVoucher snapshot = invoice.generateVoucher(accountPlan, company.isRoundingOff(), projects, resultUnits);
            snapshot.setLocalDate(LocalDate.now(clock.withZone(ZoneId.of("Europe/Stockholm"))));
            if (SSVoucherMath.getDebetSum(snapshot, accountPlan.getAccounts())
                    .compareTo(SSVoucherMath.getCreditSum(snapshot, accountPlan.getAccounts())) != 0) {
                throw new IllegalArgumentException("Unbalanced document " + invoice.getNumber()
                        + " debit=" + SSVoucherMath.getDebetSum(snapshot, accountPlan.getAccounts())
                        + " credit=" + SSVoucherMath.getCreditSum(snapshot, accountPlan.getAccounts()));
            }
            signature.writeBoundary("document").writeString(Long.toString(item.id())).writeInteger(item.legacyId())
                    .writeInteger(invoice.getNumber()).writeDate(invoice.getLocalDate());
            if (credit) signature.writeInteger(((SSCreditInvoice) invoice).getCreditingNr());
            fingerprint(signature, snapshot);
            for (SSVoucherRow row : snapshot.getRows()) journal.getRows().add(new SSVoucherRow(row));
        }
        journal = SSVoucherMath.compress(journal, accountPlan.getAccounts(), projects, resultUnits);
        if (SSVoucherMath.getDebetSum(journal, accountPlan.getAccounts())
                .compareTo(SSVoucherMath.getCreditSum(journal, accountPlan.getAccounts())) != 0) {
            throw new IllegalArgumentException("Journal is not balanced");
        }
        fingerprint(signature, journal);
        return new Plan(credit, companyId, yearId, journalNumber, voucherNumber, from, to,
                selected, journal, signature.finish());
    }

    /**
     * Revalidates accounting-relevant plan inputs and posts the complete journal under one
     * savepoint. Stale selection, amounts, dimensions, counters or ledger numbering are refused.
     * Non-posting metadata may change without invalidating the plan; fresh values are retained.
     */
    public Posted post(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) throw new SQLException("Posting requires a caller-owned transaction");
        Savepoint savepoint = connection.setSavepoint();
        try {
            Plan fresh = plan(expected.credit, expected.company, expected.year, expected.from, expected.to);
            if (!fresh.signature.equals(expected.signature)) {
                throw new IllegalArgumentException("Journal plan is stale; preview it again");
            }
            NormalizedInvoiceStore invoices = new NormalizedInvoiceStore(connection);
            for (var item : fresh.documents) {
                if (fresh.credit) invoices.markCreditInvoiceEnteredById(fresh.company, item.id(),
                        item.document().getStoredVoucher());
                else invoices.markInvoiceEnteredById(fresh.company, item.id(), item.document().getStoredVoucher());
            }
            fresh.voucher.setNumber(fresh.voucherNumber);
            new NormalizedAccountingWriter(clock).addVoucher(connection, fresh.year, fresh.voucher);
            new NormalizedRegisterStore(connection).bumpAutoIncrement(fresh.company,
                    fresh.credit ? "creditinvoicejournal" : "invoicejournal");
            connection.releaseSavepoint(savepoint);
            return new Posted(fresh.journalNumber, fresh.voucherNumber,
                    fresh.documents.stream().map(NormalizedInvoiceStore.Stored::id).toList());
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(savepoint); }
            catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private static int next(int current, String label) {
        if (current == Integer.MAX_VALUE) throw new IllegalArgumentException(label + " exhausted");
        return current + 1;
    }

    private static void fingerprint(CanonicalFingerprintWriter writer, SSVoucher voucher) {
        writer.writeBoundary("voucher").writeInteger(voucher.getNumber()).writeDate(voucher.getLocalDate())
                .writeString(voucher.getDescription()).writeInteger(voucher.getRows().size());
        for (SSVoucherRow row : voucher.getRows()) {
            writer.writeInteger(row.getAccountNr()).writeString(row.getProjectNr()).writeString(row.getResultUnitNr())
                    .writeDecimal(row.getDebet()).writeDecimal(row.getCredit())
                    .writeString(row.getLocalEditedDate() == null ? null : row.getLocalEditedDate().toString())
                    .writeString(row.getEditedSignature()).writeBoolean(row.isCrossed()).writeBoolean(row.isAdded());
        }
    }
}
