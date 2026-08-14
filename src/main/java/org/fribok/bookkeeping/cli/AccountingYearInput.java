package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** JSON input for an accounting year. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class AccountingYearInput {
    private int schemaVersion = 1;
    @NotNull private LocalDate from;
    @NotNull private LocalDate to;
    private Integer accountPlanId;
    private String accountPlanName;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate value) { from = value; }
    public LocalDate getTo() { return to; }
    public void setTo(LocalDate value) { to = value; }
    public Integer getAccountPlanId() { return accountPlanId; }
    public void setAccountPlanId(Integer value) { accountPlanId = value; }
    public String getAccountPlanName() { return accountPlanName; }
    public void setAccountPlanName(String value) { accountPlanName = value; }
}
