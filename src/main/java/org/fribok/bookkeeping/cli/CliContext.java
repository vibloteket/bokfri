package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;

/** A named selection of Bokfri data, company, and accounting year. */
public class CliContext {
    @JsonProperty("data-dir")
    private String dataDir;

    @JsonProperty("company-id")
    private Integer companyId;

    @JsonProperty("year-id")
    private Integer yearId;

    public CliContext() {}

    public CliContext(Path dataDir, Integer companyId, Integer yearId) {
        this.dataDir = dataDir.toAbsolutePath().normalize().toString();
        this.companyId = companyId;
        this.yearId = yearId;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Integer getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Integer companyId) {
        this.companyId = companyId;
    }

    public Integer getYearId() {
        return yearId;
    }

    public void setYearId(Integer yearId) {
        this.yearId = yearId;
    }
}
