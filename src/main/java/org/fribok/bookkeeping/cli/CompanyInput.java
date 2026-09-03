package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** JSON input for a company. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class CompanyInput {
    private int schemaVersion = 1;
    private String name;
    private String corporateId;
    private String vatNumber;
    private String email;
    private String phone;
    private String contactPerson;
    private String bankgiro;
    private String logotype;
    private String currency = "SEK";
    private String paymentTerms = "30";
    private String standardUnit = "st";
    private Integer vatPeriod = 1;
    private BigDecimal reminderFee = BigDecimal.ZERO;
    private BigDecimal delayInterest = BigDecimal.ZERO;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getCorporateId() { return corporateId; }
    public void setCorporateId(String value) { corporateId = value; }
    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String value) { vatNumber = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String value) { contactPerson = value; }
    public String getBankgiro() { return bankgiro; }
    public void setBankgiro(String value) { bankgiro = value; }
    public String getLogotype() { return logotype; }
    public void setLogotype(String value) { logotype = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getPaymentTerms() { return paymentTerms; }
    public void setPaymentTerms(String value) { paymentTerms = value; }
    public String getStandardUnit() { return standardUnit; }
    public void setStandardUnit(String value) { standardUnit = value; }
    public Integer getVatPeriod() { return vatPeriod; }
    public void setVatPeriod(Integer value) { vatPeriod = value; }
    public BigDecimal getReminderFee() { return reminderFee; }
    public void setReminderFee(BigDecimal value) { reminderFee = value; }
    public BigDecimal getDelayInterest() { return delayInterest; }
    public void setDelayInterest(BigDecimal value) { delayInterest = value; }
}
