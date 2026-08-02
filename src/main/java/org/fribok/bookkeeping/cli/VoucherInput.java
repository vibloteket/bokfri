package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** JSON input contract for validating or creating a manual voucher. */
@JsonIgnoreProperties(ignoreUnknown = false)
public class VoucherInput {
    private int schemaVersion = 1;
    private LocalDate date;
    private String description;
    private List<Row> rows = new ArrayList<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Row> getRows() {
        return rows;
    }

    public void setRows(List<Row> rows) {
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    /** One posting row in the JSON input contract. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Row {
        private Integer account;
        private BigDecimal debit;
        private BigDecimal credit;
        private String project;
        private String resultUnit;

        public Integer getAccount() {
            return account;
        }

        public void setAccount(Integer account) {
            this.account = account;
        }

        public BigDecimal getDebit() {
            return debit;
        }

        public void setDebit(BigDecimal debit) {
            this.debit = debit;
        }

        public BigDecimal getCredit() {
            return credit;
        }

        public void setCredit(BigDecimal credit) {
            this.credit = credit;
        }

        public String getProject() {
            return project;
        }

        public void setProject(String project) {
            this.project = project;
        }

        public String getResultUnit() {
            return resultUnit;
        }

        public void setResultUnit(String resultUnit) {
            this.resultUnit = resultUnit;
        }
    }
}
