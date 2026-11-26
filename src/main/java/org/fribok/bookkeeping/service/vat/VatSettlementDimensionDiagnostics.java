package org.fribok.bookkeeping.service.vat;

import org.fribok.bookkeeping.service.vat.VatSettlementDiagnostics.Finding;
import org.fribok.bookkeeping.service.vat.VatSettlementDiagnostics.Kind;

import se.swedsoft.bookkeeping.data.SSVoucher;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Explicit proposed-key observations, not complete bookability or posting authorization. */
public final class VatSettlementDimensionDiagnostics {
    private VatSettlementDimensionDiagnostics() {}

    /** Copied project identity/closure state; no mutable project or ambient resolver escapes. */
    public record Project(String number, boolean concluded, LocalDate concludedOn) {}

    /** Immutable selected-company catalogue. Duplicate or malformed identities fail closed. */
    public record Catalogue(List<Project> projects, List<String> units) {
        public Catalogue {
            projects = List.copyOf(projects);
            units = List.copyOf(units);
            var projectKeys = new HashSet<String>();
            for (var project : projects) {
                requireKey(project.number());
                if (!projectKeys.add(project.number())) {
                    throw new IllegalArgumentException("Duplicate project identity");
                }
            }
            var unitKeys = new HashSet<String>();
            for (var unit : units) {
                requireKey(unit);
                if (!unitKeys.add(unit)) {
                    throw new IllegalArgumentException("Duplicate unit identity");
                }
            }
        }
    }

    /**
     * Observes raw keys on actual proposed rows against an explicit same-company catalogue. Null
     * optional keys need no identity; blank keys are not repaired. Closure is observed at proposal
     * date, not historical source date. No source history or posting policy is approved.
     *
     * @param proposal unnumbered dated proposal; no ambient or mutating row getters are used
     * @param catalogue immutable selected-company project/unit snapshots
     * @return immutable deduplicated observations in proposed-row order, without invented sources
     */
    public static List<Finding> inspect(SSVoucher proposal, Catalogue catalogue) {
        Objects.requireNonNull(proposal, "explicit proposal");
        Objects.requireNonNull(catalogue, "explicit catalogue");
        if (proposal.getNumber() != 0 || proposal.getLocalDate() == null) {
            throw new IllegalArgumentException("Dated unnumbered proposal required");
        }
        var projects = new HashMap<String, Project>();
        for (var project : catalogue.projects()) projects.put(project.number(), project);
        var units = new HashSet<>(catalogue.units());
        var findings = new LinkedHashSet<Finding>();
        for (var row : proposal.getRows()) {
            Integer account = Objects.requireNonNull(row.getAccountNr(), "proposed account");
            String project = row.getProjectNr();
            if (project != null) {
                Kind kind = null;
                if (project.isBlank()) {
                    kind = Kind.PROJECT_KEY_BLANK;
                } else if (!projects.containsKey(project)) {
                    kind = Kind.UNKNOWN_PROJECT;
                } else {
                    var state = projects.get(project);
                    if (state.concluded()) {
                        if (state.concludedOn() == null) {
                            kind = Kind.PROJECT_CLOSURE_DATE_UNKNOWN;
                        } else if (!state.concludedOn().isAfter(proposal.getLocalDate())) {
                            kind = Kind.PROJECT_CLOSED_ON_DATE;
                        }
                    }
                }
                if (kind != null) findings.add(new Finding(kind, account, null, null, project));
            }
            String unit = row.getResultUnitNr();
            if (unit != null) {
                Kind kind =
                        unit.isBlank()
                                ? Kind.UNIT_KEY_BLANK
                                : units.contains(unit) ? null : Kind.UNKNOWN_UNIT;
                if (kind != null) findings.add(new Finding(kind, account, null, null, unit));
            }
        }
        return List.copyOf(findings);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Known nonblank catalogue identity required");
        }
    }
}
