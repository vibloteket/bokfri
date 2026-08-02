package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned CLI configuration stored outside the bookkeeping database. */
public class CliConfig {
    private int version = 1;

    @JsonProperty("current-context")
    private String currentContext;

    private Map<String, CliContext> contexts = new LinkedHashMap<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getCurrentContext() {
        return currentContext;
    }

    public void setCurrentContext(String currentContext) {
        this.currentContext = currentContext;
    }

    public Map<String, CliContext> getContexts() {
        return contexts;
    }

    public void setContexts(Map<String, CliContext> contexts) {
        this.contexts = contexts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(contexts);
    }
}
