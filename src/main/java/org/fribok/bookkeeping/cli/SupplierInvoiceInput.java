package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** JSON input contract for a supplier invoice. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class SupplierInvoiceInput {
    private int schemaVersion = 1;
    @jakarta.validation.constraints.NotBlank
    private String supplierNumber;
    @jakarta.validation.constraints.NotNull
    private LocalDate date;
    private LocalDate dueDate;
    private String reference;
    private BigDecimal vat;
    private BigDecimal rounding;
    @jakarta.validation.Valid
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Size(min = 1)
    private List<Row> rows = new ArrayList<>();
    public int getSchemaVersion(){return schemaVersion;} public void setSchemaVersion(int v){schemaVersion=v;}
    public String getSupplierNumber(){return supplierNumber;} public void setSupplierNumber(String v){supplierNumber=v;}
    public LocalDate getDate(){return date;} public void setDate(LocalDate v){date=v;}
    public LocalDate getDueDate(){return dueDate;} public void setDueDate(LocalDate v){dueDate=v;}
    public String getReference(){return reference;} public void setReference(String v){reference=v;}
    public BigDecimal getVat(){return vat;} public void setVat(BigDecimal v){vat=v;}
    public BigDecimal getRounding(){return rounding;} public void setRounding(BigDecimal v){rounding=v;}
    public List<Row> getRows(){return rows;} public void setRows(List<Row> v){rows=v==null?new ArrayList<>():v;}

    /** One supplier invoice cost row. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Row {
        private String productNumber; private String description; private Integer quantity;
        private BigDecimal unitPrice; private BigDecimal freight; private Integer account;
        private String unit; private String project; private String resultUnit;
        public String getProductNumber(){return productNumber;} public void setProductNumber(String v){productNumber=v;}
        public String getDescription(){return description;} public void setDescription(String v){description=v;}
        public Integer getQuantity(){return quantity;} public void setQuantity(Integer v){quantity=v;}
        public BigDecimal getUnitPrice(){return unitPrice;} public void setUnitPrice(BigDecimal v){unitPrice=v;}
        public BigDecimal getFreight(){return freight;} public void setFreight(BigDecimal v){freight=v;}
        public Integer getAccount(){return account;} public void setAccount(Integer v){account=v;}
        public String getUnit(){return unit;} public void setUnit(String v){unit=v;}
        public String getProject(){return project;} public void setProject(String v){project=v;}
        public String getResultUnit(){return resultUnit;} public void setResultUnit(String v){resultUnit=v;}
    }
}
