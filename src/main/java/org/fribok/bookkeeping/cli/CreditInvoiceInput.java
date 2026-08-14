package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/** JSON input for crediting a customer invoice. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class CreditInvoiceInput {
    private int schemaVersion = 1;
    @jakarta.validation.constraints.NotNull
    private Integer invoiceNumber;
    @jakarta.validation.constraints.NotNull
    private LocalDate date;
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Positive
    private BigDecimal amount;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public Integer getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(Integer value) { invoiceNumber = value; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate value) { date = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
}
