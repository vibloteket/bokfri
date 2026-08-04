package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** JSON input contract for validating or creating a customer. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class CustomerInput {
    private int schemaVersion = 1;
    private String number;
    private String name;
    private String registrationNumber;
    private String vatNumber;
    private String email;
    private String phone;
    private String ourContact;
    private String yourContact;
    private String currency;
    private String paymentTerms;
    private Boolean taxFree;
    private BigDecimal discount;
    private String comment;
    private Address invoiceAddress;
    private Address deliveryAddress;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String vatNumber) { this.vatNumber = vatNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getOurContact() { return ourContact; }
    public void setOurContact(String ourContact) { this.ourContact = ourContact; }
    public String getYourContact() { return yourContact; }
    public void setYourContact(String yourContact) { this.yourContact = yourContact; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentTerms() { return paymentTerms; }
    public void setPaymentTerms(String paymentTerms) { this.paymentTerms = paymentTerms; }
    public Boolean getTaxFree() { return taxFree; }
    public void setTaxFree(Boolean taxFree) { this.taxFree = taxFree; }
    public BigDecimal getDiscount() { return discount; }
    public void setDiscount(BigDecimal discount) { this.discount = discount; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Address getInvoiceAddress() { return invoiceAddress; }
    public void setInvoiceAddress(Address invoiceAddress) { this.invoiceAddress = invoiceAddress; }
    public Address getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(Address deliveryAddress) { this.deliveryAddress = deliveryAddress; }

    /** Postal address in the JSON customer contract. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Address {
        private String name;
        private String address1;
        private String address2;
        private String postalCode;
        private String city;
        private String country;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddress1() { return address1; }
        public void setAddress1(String address1) { this.address1 = address1; }
        public String getAddress2() { return address2; }
        public void setAddress2(String address2) { this.address2 = address2; }
        public String getPostalCode() { return postalCode; }
        public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
    }
}
