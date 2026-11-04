package se.swedsoft.bookkeeping.print.report.journals;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit lookup context for journal voucher snapshots; never invokes singleton-backed row accessors. */
final class SSJournalRowContext {
    private final Map<Integer, SSAccount> accounts = new HashMap<>();
    private final Set<String> projects = new HashSet<>();
    private final Set<String> resultUnits = new HashSet<>();

    SSJournalRowContext(SSNewAccountingYear year, List<SSNewProject> projects, List<SSNewResultUnit> resultUnits) {
        for (SSAccount account : year.getAccountPlan().getAccounts()) {
            accounts.putIfAbsent(account.getNumber(), account);
        }
        for (SSNewProject project : projects) this.projects.add(project.getNumber());
        for (SSNewResultUnit unit : resultUnits) this.resultUnits.add(unit.getNumber());
    }

    SSAccount account(SSVoucherRow row) { return accounts.get(row.getAccountNr()); }
    String project(SSVoucherRow row) { return projects.contains(row.getProjectNr()) ? row.getProjectNr() : null; }
    String resultUnit(SSVoucherRow row) {
        return resultUnits.contains(row.getResultUnitNr()) ? row.getResultUnitNr() : null;
    }
}
