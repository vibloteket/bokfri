package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/** JSON input for crediting a supplier invoice. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class SupplierCreditInvoiceInput {
    private int schemaVersion = 1;
    private Integer supplierInvoiceNumber;
    private LocalDate date;
    private BigDecimal amount;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public Integer getSupplierInvoiceNumber() { return supplierInvoiceNumber; }
    public void setSupplierInvoiceNumber(Integer value) { supplierInvoiceNumber = value; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate value) { date = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
}
