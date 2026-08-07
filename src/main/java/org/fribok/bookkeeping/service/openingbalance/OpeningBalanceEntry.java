package org.fribok.bookkeeping.service.openingbalance; import java.math.BigDecimal;
public record OpeningBalanceEntry(int account,String description,BigDecimal amount){}
