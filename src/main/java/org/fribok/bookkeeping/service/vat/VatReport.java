package org.fribok.bookkeeping.service.vat;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.List;
/** Calculated Swedish VAT report values. */
public record VatReport(LocalDate from, LocalDate to, List<VatReportBox> boxes, BigDecimal vatToPayOrRefund){public VatReport{boxes=List.copyOf(boxes);}}
