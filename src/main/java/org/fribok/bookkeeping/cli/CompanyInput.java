package org.fribok.bookkeeping.cli;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/** JSON input for a company. */
@JsonIgnoreProperties(ignoreUnknown=false)
public class CompanyInput {
 private int schemaVersion=1; private String name; private String corporateId; private String vatNumber; private String email; private String phone; private String contactPerson; private String currency="SEK"; private String paymentTerms="30"; private String standardUnit="st"; private Integer vatPeriod=1;
 public int getSchemaVersion(){return schemaVersion;} public void setSchemaVersion(int v){schemaVersion=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getCorporateId(){return corporateId;} public void setCorporateId(String v){corporateId=v;} public String getVatNumber(){return vatNumber;} public void setVatNumber(String v){vatNumber=v;} public String getEmail(){return email;} public void setEmail(String v){email=v;} public String getPhone(){return phone;} public void setPhone(String v){phone=v;} public String getContactPerson(){return contactPerson;} public void setContactPerson(String v){contactPerson=v;} public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;} public String getPaymentTerms(){return paymentTerms;} public void setPaymentTerms(String v){paymentTerms=v;} public String getStandardUnit(){return standardUnit;} public void setStandardUnit(String v){standardUnit=v;} public Integer getVatPeriod(){return vatPeriod;} public void setVatPeriod(Integer v){vatPeriod=v;}
}
