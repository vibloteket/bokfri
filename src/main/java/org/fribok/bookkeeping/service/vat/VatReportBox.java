package org.fribok.bookkeeping.service.vat;
import java.math.BigDecimal;
/** One Swedish VAT declaration box. */
public record VatReportBox(int number, String vatCodes, BigDecimal amount) {}
