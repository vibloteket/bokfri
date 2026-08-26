package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** JSON input contract for validating or creating a product. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class ProductInput {
    private int schemaVersion = 1;
    @jakarta.validation.constraints.NotBlank
    private String number;
    @jakarta.validation.constraints.NotBlank
    private String description;
    @jakarta.validation.constraints.NotNull
    private BigDecimal sellingPrice;
    @jakarta.validation.constraints.NotNull
    private BigDecimal vatRate;
    private String unit;
    @jakarta.validation.constraints.NotNull
    private Integer salesAccount;
    private String project;
    private String resultUnit;
    private Boolean stockProduct;
    private Boolean expired;
    private BigDecimal weight;
    private BigDecimal volume;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getSellingPrice() { return sellingPrice; }
    public void setSellingPrice(BigDecimal sellingPrice) { this.sellingPrice = sellingPrice; }
    public BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(BigDecimal vatRate) { this.vatRate = vatRate; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Integer getSalesAccount() { return salesAccount; }
    public void setSalesAccount(Integer salesAccount) { this.salesAccount = salesAccount; }
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }
    public String getResultUnit() { return resultUnit; }
    public void setResultUnit(String resultUnit) { this.resultUnit = resultUnit; }
    public Boolean getStockProduct() { return stockProduct; }
    public void setStockProduct(Boolean stockProduct) { this.stockProduct = stockProduct; }
    public Boolean getExpired() { return expired; }
    public void setExpired(Boolean expired) { this.expired = expired; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public BigDecimal getVolume() { return volume; }
    public void setVolume(BigDecimal volume) { this.volume = volume; }
}
