package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** JSON input contract for validating or creating a supplier. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class SupplierInput {
    private int schemaVersion = 1;
    @jakarta.validation.constraints.NotBlank
    private String number;
    @jakarta.validation.constraints.NotBlank
    private String name;
    private String registrationNumber;
    private String email;
    private String phone;
    private String homepage;
    private String ourContact;
    private String yourContact;
    private String ourCustomerNumber;
    private String bankgiro;
    private String plusgiro;
    private Integer outpaymentNumber;
    private String currency;
    private String paymentTerms;
    private String comment;
    private CustomerInput.Address address;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getHomepage() { return homepage; }
    public void setHomepage(String homepage) { this.homepage = homepage; }
    public String getOurContact() { return ourContact; }
    public void setOurContact(String ourContact) { this.ourContact = ourContact; }
    public String getYourContact() { return yourContact; }
    public void setYourContact(String yourContact) { this.yourContact = yourContact; }
    public String getOurCustomerNumber() { return ourCustomerNumber; }
    public void setOurCustomerNumber(String ourCustomerNumber) { this.ourCustomerNumber = ourCustomerNumber; }
    public String getBankgiro() { return bankgiro; }
    public void setBankgiro(String bankgiro) { this.bankgiro = bankgiro; }
    public String getPlusgiro() { return plusgiro; }
    public void setPlusgiro(String plusgiro) { this.plusgiro = plusgiro; }
    public Integer getOutpaymentNumber() { return outpaymentNumber; }
    public void setOutpaymentNumber(Integer outpaymentNumber) { this.outpaymentNumber = outpaymentNumber; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentTerms() { return paymentTerms; }
    public void setPaymentTerms(String paymentTerms) { this.paymentTerms = paymentTerms; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public CustomerInput.Address getAddress() { return address; }
    public void setAddress(CustomerInput.Address address) { this.address = address; }
}
