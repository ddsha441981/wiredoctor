/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Snapshot of Spring Boot's autoconfiguration condition evaluation — the same
 * data the {@code --debug} condition report is rendered from, captured in a
 * diffable shape (v0.5.0, the "Upgrade Guardian").
 * <p>
 * Per autoconfiguration class, exactly ONE stable outcome:
 * <ul>
 *   <li>{@code matched} — all conditions passed, the autoconfiguration applied</li>
 *   <li>{@code notMatched} — evaluated but a condition said no (first non-match
 *       message captured as {@code reason})</li>
 *   <li>{@code excluded} — explicitly excluded by the user</li>
 *   <li>{@code unconditional} — no conditions, always applied</li>
 * </ul>
 * Class names are the diff key, not messages: outcome semantics are stable
 * across Boot versions while message wording is not.
 * <p>
 * Read-only access to the {@link ConditionEvaluationReport} Boot already keeps
 * in memory — zero new intrusion. Extraction failures degrade to an empty
 * snapshot (section skipped), never an error: exotic contexts may not carry
 * the report at all.
 *
 * @author Deendayal Kumawat
 * @since 0.5.0
 */
public final class WireDoctorConditionSnapshot {

    /** Outcome constants — the JSON contract, do not rename. */
    static final String MATCHED       = "matched";
    static final String NOT_MATCHED   = "notMatched";
    static final String EXCLUDED      = "excluded";
    static final String UNCONDITIONAL = "unconditional";

    private WireDoctorConditionSnapshot() {
        // static utility
    }

    /**
     * One evaluated autoconfiguration: its outcome plus, for non-matches, the
     * first non-match message as a human-readable reason.
     */
    public static final class Outcome {
        private final String outcome;
        private final String reason; // null except for notMatched

        Outcome(String outcome, String reason) {
            this.outcome = outcome;
            this.reason = reason;
        }

        public String outcome() { return outcome; }
        public String reason()  { return reason; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Outcome other)) return false;
            // Reason is informational — the OUTCOME is the diff signal. Two
            // notMatched entries with differently-worded reasons are equal.
            return outcome.equals(other.outcome);
        }

        @Override
        public int hashCode() {
            return outcome.hashCode();
        }
    }

    /**
     * Extracts per-autoconfiguration outcomes from the live condition report.
     * <p>
     * Sorted map (class name ascending) for deterministic JSON — baselines are
     * committed to git, and stable ordering keeps their diffs reviewable.
     *
     * @param report the context's condition evaluation report, or {@code null}
     * @return class name → outcome; empty when the report is null or unreadable
     */
    public static Map<String, Outcome> extract(ConditionEvaluationReport report) {
        Map<String, Outcome> outcomes = new TreeMap<>();
        if (report == null) {
            return outcomes;
        }
        try {
            report.getConditionAndOutcomesBySource().forEach((source, conditionOutcomes) -> {
                if (conditionOutcomes.isFullMatch()) {
                    outcomes.put(source, new Outcome(MATCHED, null));
                } else {
                    String reason = null;
                    for (ConditionEvaluationReport.ConditionAndOutcome co : conditionOutcomes) {
                        ConditionOutcome outcome = co.getOutcome();
                        if (!outcome.isMatch()) {
                            reason = outcome.getMessage();
                            break;
                        }
                    }
                    outcomes.put(source, new Outcome(NOT_MATCHED, reason));
                }
            });
            report.getExclusions().forEach(excluded ->
                    outcomes.put(excluded, new Outcome(EXCLUDED, null)));
            report.getUnconditionalClasses().forEach(unconditional ->
                    outcomes.put(unconditional, new Outcome(UNCONDITIONAL, null)));
        } catch (Exception e) {
            // Never let condition extraction disturb the analysis — the section
            // is additive; an empty map means "skipped", handled upstream.
            return new TreeMap<>();
        }
        return outcomes;
    }

    /**
     * Serializes outcomes into the report/baseline shape:
     * {@code {"className": {"outcome": "...", "reason": "..."}}} — reason
     * omitted when null.
     *
     * @param outcomes the extracted outcomes
     * @return an ordered map ready for Jackson
     */
    public static Map<String, Object> toReportMap(Map<String, Outcome> outcomes) {
        Map<String, Object> map = new LinkedHashMap<>();
        outcomes.forEach((className, o) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("outcome", o.outcome());
            if (o.reason() != null) {
                entry.put("reason", o.reason());
            }
            map.put(className, entry);
        });
        return map;
    }

    /**
     * Parses a {@code conditions} section back out of a baseline/report JSON.
     *
     * @param conditionsNode the {@code conditions} node (may be missing)
     * @return class name → outcome; {@code null} when the section is absent —
     *         the caller-visible signal that the baseline predates condition
     *         tracking and the condition diff must be skipped, not reported
     *         as "everything changed"
     */
    public static Map<String, Outcome> fromJson(JsonNode conditionsNode) {
        if (conditionsNode == null || conditionsNode.isMissingNode() || !conditionsNode.isObject()) {
            return null;
        }
        Map<String, Outcome> outcomes = new TreeMap<>();
        conditionsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String outcome = value.path("outcome").asText(null);
            if (outcome != null) {
                String reason = value.hasNonNull("reason") ? value.get("reason").asText() : null;
                outcomes.put(entry.getKey(), new Outcome(outcome, reason));
            }
        });
        return outcomes;
    }
}
