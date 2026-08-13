package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** JSON input contract for validating or creating a customer invoice. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class InvoiceInput {
    private int schemaVersion = 1;
    private String customerNumber;
    private LocalDate date;
    private LocalDate dueDate;
    private String yourOrderNumber;
    private String text;
    private List<Row> rows = new ArrayList<>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getCustomerNumber() { return customerNumber; }
    public void setCustomerNumber(String customerNumber) { this.customerNumber = customerNumber; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getYourOrderNumber() { return yourOrderNumber; }
    public void setYourOrderNumber(String yourOrderNumber) { this.yourOrderNumber = yourOrderNumber; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows == null ? new ArrayList<>() : rows; }

    /** One product-based or free-text invoice row. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Row {
        private String productNumber;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal discount;
        private BigDecimal vatRate;
        private Integer salesAccount;
        private String unit;
        private String project;
        private String resultUnit;

        public String getProductNumber() { return productNumber; }
        public void setProductNumber(String productNumber) { this.productNumber = productNumber; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public BigDecimal getDiscount() { return discount; }
        public void setDiscount(BigDecimal discount) { this.discount = discount; }
        public BigDecimal getVatRate() { return vatRate; }
        public void setVatRate(BigDecimal vatRate) { this.vatRate = vatRate; }
        public Integer getSalesAccount() { return salesAccount; }
        public void setSalesAccount(Integer salesAccount) { this.salesAccount = salesAccount; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getProject() { return project; }
        public void setProject(String project) { this.project = project; }
        public String getResultUnit() { return resultUnit; }
        public void setResultUnit(String resultUnit) { this.resultUnit = resultUnit; }
    }
}
