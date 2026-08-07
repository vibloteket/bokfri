package org.fribok.bookkeeping.cli;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; import java.time.LocalDate;
/** JSON input for an accounting year. */
@JsonIgnoreProperties(ignoreUnknown=false)
public class AccountingYearInput {private int schemaVersion=1;private LocalDate from;private LocalDate to;private Integer accountPlanId;private String accountPlanName;public int getSchemaVersion(){return schemaVersion;}public void setSchemaVersion(int v){schemaVersion=v;}public LocalDate getFrom(){return from;}public void setFrom(LocalDate v){from=v;}public LocalDate getTo(){return to;}public void setTo(LocalDate v){to=v;}public Integer getAccountPlanId(){return accountPlanId;}public void setAccountPlanId(Integer v){accountPlanId=v;}public String getAccountPlanName(){return accountPlanName;}public void setAccountPlanName(String v){accountPlanName=v;}}
