package org.fribok.bookkeeping.service.openingbalance;

import java.math.BigDecimal;
import java.util.List;

/** Preview or result of an opening-balance operation. */
public record OpeningBalancePlan(List<OpeningBalanceEntry> balances, BigDecimal debitTotal,
                                 BigDecimal creditTotal, BigDecimal difference,
                                 OpeningBalanceAdjustment adjustment) {
    public OpeningBalancePlan {
        balances = List.copyOf(balances);
    }

    public OpeningBalancePlan(List<OpeningBalanceEntry> balances, BigDecimal debitTotal,
                              BigDecimal creditTotal, BigDecimal difference) {
        this(balances, debitTotal, creditTotal, difference, null);
    }
}
