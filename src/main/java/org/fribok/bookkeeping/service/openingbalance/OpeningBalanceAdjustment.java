package org.fribok.bookkeeping.service.openingbalance;

import java.math.BigDecimal;

/** One visible öre adjustment proposed while carrying balances into a new year. */
public record OpeningBalanceAdjustment(int account, String description, BigDecimal before,
                                       BigDecimal after, BigDecimal amount) {}
