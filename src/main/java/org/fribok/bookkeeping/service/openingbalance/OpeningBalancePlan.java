package org.fribok.bookkeeping.service.openingbalance; import java.math.BigDecimal; import java.util.List;
public record OpeningBalancePlan(List<OpeningBalanceEntry> balances,BigDecimal debitTotal,BigDecimal creditTotal,BigDecimal difference){public OpeningBalancePlan{balances=List.copyOf(balances);}}
