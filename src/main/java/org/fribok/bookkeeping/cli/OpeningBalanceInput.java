package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** JSON input for replacing an accounting year's opening balances. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class OpeningBalanceInput {
    private int schemaVersion = 1;
    @Valid
    @NotNull
    @Size(min = 1)
    private List<Row> balances = new ArrayList<>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public List<Row> getBalances() { return balances; }
    public void setBalances(List<Row> value) { balances = value == null ? new ArrayList<>() : value; }

    /** One account balance. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Row {
        @NotNull private Integer account;
        @NotNull private BigDecimal amount;
        public Integer getAccount() { return account; }
        public void setAccount(Integer value) { account = value; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal value) { amount = value; }
    }
}
