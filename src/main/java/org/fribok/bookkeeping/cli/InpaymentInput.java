package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** JSON input contract for validating or creating a customer inpayment. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class InpaymentInput {
    private int schemaVersion = 1;
    @jakarta.validation.constraints.NotNull
    private LocalDate date;
    @jakarta.validation.constraints.NotBlank
    private String text;
    @jakarta.validation.Valid
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Size(min = 1)
    private List<Row> rows = new ArrayList<>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows == null ? new ArrayList<>() : rows; }

    /** One invoice payment row. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Row {
        @jakarta.validation.constraints.NotNull
        private Integer invoiceNumber;
        @jakarta.validation.constraints.NotNull
        @jakarta.validation.constraints.Positive
        private BigDecimal amount;
        private BigDecimal currencyRate;

        public Integer getInvoiceNumber() { return invoiceNumber; }
        public void setInvoiceNumber(Integer invoiceNumber) { this.invoiceNumber = invoiceNumber; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public BigDecimal getCurrencyRate() { return currencyRate; }
        public void setCurrencyRate(BigDecimal currencyRate) { this.currencyRate = currencyRate; }
    }
}
