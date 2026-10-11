package se.swedsoft.bookkeeping.calc;


import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date: 2006-feb-23
 * Time: 15:39:06
 */
public class SSSaldoCalculator {    private static final Logger LOG = LoggerFactory.getLogger(SSSaldoCalculator.class);

    private SSSaldoCalculator() {}

    /**
     *
     * @param iMap
     * @param iAccount
     * @param iValue
     */
    private static void addValueToMap(Map<SSAccount, BigDecimal> iMap, SSAccount iAccount, BigDecimal iValue) {
        // Add the row sum to the total sum
        BigDecimal s = iMap.get(iAccount);

        if (s == null) {
            iMap.put(iAccount, iValue);
        } else {
            iMap.put(iAccount, s.add(iValue));
        }
    }

    /**
     *
     * @param pYearData
     * @param pMonth
     * @return
     */
    public static Map<SSAccount, BigDecimal> getSaldo(SSNewAccountingYear pYearData, SSMonth pMonth) {
        List<SSVoucher> iVouchers = pYearData.getVouchers();

        Map<SSAccount, BigDecimal> iResult = new HashMap<>();

        // Loop through all vouchers
        for (SSVoucher iVoucher: iVouchers) {

            if (!SSVoucherMath.inPeriod(iVoucher, pMonth)) {
                continue;
            }

            // Loop through all the voucher rows
            for (SSVoucherRow iRow: iVoucher.getRows()) {
                SSAccount iRowAccount = iRow.getAccount();

                // Skip the row is crossed or invalid
                if (!iRow.isValid() || iRow.isCrossed()) {
                    continue;
                }

                BigDecimal iRowSum = SSVoucherMath.getDebetMinusCredit(iRow);

                addValueToMap(iResult, iRowAccount, iRowSum);
            }

        }
        return iResult;
    }

    /**
     *
     * @param pYearData
     * @param pProject
     * @param pMonth
     * @return
     */
    public static Map<SSAccount, BigDecimal> getSaldo(SSNewAccountingYear pYearData, SSNewProject pProject, SSMonth pMonth) {
        return getSaldo(pYearData, pProject, pMonth, null);
    }

    /** Monthly saldo with explicit project resolution for normalized storage. */
    public static Map<SSAccount, BigDecimal> getSaldo(SSNewAccountingYear pYearData,
            SSNewProject pProject, SSMonth pMonth, List<SSNewProject> projects) {
        List<SSVoucher> iVouchers = pYearData.getVouchers();

        Map<SSAccount, BigDecimal> iResult = new HashMap<>();

        // Loop through all vouchers
        for (SSVoucher iVoucher: iVouchers) {

            if (!SSVoucherMath.inPeriod(iVoucher, pMonth)) {
                continue;
            }

            // Loop through all the voucher rows
            for (SSVoucherRow iRow: iVoucher.getRows()) {
                SSAccount iRowAccount = iRow.getAccount();
                SSNewProject iRowProject = projects == null ? iRow.getProject() : iRow.getProject(projects);

                if (iRowProject != null) {
                    // Skip the row is crossed or invalid
                    if (!iRow.isValid() || iRow.isCrossed()
                            || !iRowProject.equals(pProject)) {
                        continue;
                    }

                    BigDecimal iRowSum = SSVoucherMath.getDebetMinusCredit(iRow);

                    addValueToMap(iResult, iRowAccount, iRowSum);
                }
            }

        }
        return iResult;
    }

    /**
     *
     * @param pYearData
     * @param pResultUnit
     * @param pMonth
     * @return
     */
    public static Map<SSAccount, BigDecimal> getSaldo(SSNewAccountingYear pYearData, SSNewResultUnit pResultUnit, SSMonth pMonth) {
        return getSaldo(pYearData, pResultUnit, pMonth, null);
    }

    /** Monthly saldo with explicit result-unit resolution for normalized storage. */
    public static Map<SSAccount, BigDecimal> getSaldo(SSNewAccountingYear pYearData,
            SSNewResultUnit pResultUnit, SSMonth pMonth, List<SSNewResultUnit> resultUnits) {
        List<SSVoucher> iVouchers = pYearData.getVouchers();

        Map<SSAccount, BigDecimal> iResult = new HashMap<>();

        // Loop through all vouchers
        for (SSVoucher iVoucher: iVouchers) {

            if (!SSVoucherMath.inPeriod(iVoucher, pMonth)) {
                continue;
            }

            // Loop through all the voucher rows
            for (SSVoucherRow iRow: iVoucher.getRows()) {
                SSAccount    iRowAccount = iRow.getAccount();
                SSNewResultUnit iRowResultUnit = resultUnits == null
                        ? iRow.getResultUnit() : iRow.getResultUnit(resultUnits);

                if (iRowResultUnit != null) {
                    // Skip the row is crossed or invalid
                    if (!iRow.isValid() || iRow.isCrossed()
                            || !iRowResultUnit.equals(pResultUnit)) {
                        continue;
                    }

                    BigDecimal iRowSum = SSVoucherMath.getDebetMinusCredit(iRow);

                    addValueToMap(iResult, iRowAccount, iRowSum);
                }
            }

        }
        return iResult;
    }

    /**
     *
     * @param pYearData
     * @return
     */
    public static Map<SSMonth, Map<SSAccount, BigDecimal>> getSaldo(SSNewAccountingYear pYearData) {
        List<SSMonth> iMonths = SSMonth.splitYearIntoMonths(pYearData);

        Map<SSMonth, Map<SSAccount, BigDecimal>> iResult = new HashMap<>();

        for (SSMonth iMonth: iMonths) {
            Map<SSAccount, BigDecimal> iSaldo = getSaldo(pYearData, iMonth);

            iResult.put(iMonth, iSaldo);
        }
        return iResult;
    }

    /**
     *
     * @param pYearData
     * @param pProject
     * @return
     */
    public static Map<SSMonth, Map<SSAccount, BigDecimal>> getSaldo(SSNewAccountingYear pYearData, SSNewProject pProject) {
        return getSaldo(pYearData, pProject, (List<SSNewProject>) null);
    }

    /** Year saldo with explicit dimension resolution for normalized storage. */
    public static Map<SSMonth, Map<SSAccount, BigDecimal>> getSaldo(SSNewAccountingYear pYearData,
            SSNewProject pProject, List<SSNewProject> projects) {
        List<SSMonth> iMonths = SSMonth.splitYearIntoMonths(pYearData);

        Map<SSMonth, Map<SSAccount, BigDecimal>> iResult = new HashMap<>();

        for (SSMonth iMonth: iMonths) {
            LOG.info("{}", iMonth);
            Map<SSAccount, BigDecimal> iSaldo = getSaldo(pYearData, pProject, iMonth, projects);

            iResult.put(iMonth, iSaldo);
        }
        return iResult;
    }

    /**
     *
     * @param pYearData
     * @param pResultUnit
     * @return
     */
    public static Map<SSMonth, Map<SSAccount, BigDecimal>> getSaldo(SSNewAccountingYear pYearData, SSNewResultUnit pResultUnit) {
        return getSaldo(pYearData, pResultUnit, (List<SSNewResultUnit>) null);
    }

    /** Year saldo with explicit dimension resolution for normalized storage. */
    public static Map<SSMonth, Map<SSAccount, BigDecimal>> getSaldo(SSNewAccountingYear pYearData,
            SSNewResultUnit pResultUnit, List<SSNewResultUnit> resultUnits) {
        List<SSMonth> iMonths = SSMonth.splitYearIntoMonths(pYearData);

        Map<SSMonth, Map<SSAccount, BigDecimal>> iResult = new HashMap<>();

        for (SSMonth iMonth: iMonths) {
            LOG.info("{}", iMonth);
            Map<SSAccount, BigDecimal> iSaldo = getSaldo(pYearData, pResultUnit, iMonth, resultUnits);

            iResult.put(iMonth, iSaldo);
        }
        return iResult;
    }

}
