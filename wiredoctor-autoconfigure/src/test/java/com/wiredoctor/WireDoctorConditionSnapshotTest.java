/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorConditionSnapshot} (v0.5.0).
 * <p>
 * Extraction runs against a real {@link ConditionEvaluationReport} produced by
 * an {@link ApplicationContextRunner} — not a mock — so the test breaks if a
 * Boot upgrade changes the report API semantics we depend on.
 */
class WireDoctorConditionSnapshotTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    WireDoctorAutoConfiguration.class));

    // ── extract ──────────────────────────────────────────────────────────────

    @Test
    void nullReportYieldsEmptySnapshot() {
        assertThat(WireDoctorConditionSnapshot.extract(null)).isEmpty();
    }

    @Test
    void extractCapturesMatchedAndNotMatchedOutcomes() {
        runner.run(context -> {
            ConditionEvaluationReport report =
                    ConditionEvaluationReport.get(context.getSourceApplicationContext().getBeanFactory());
            Map<String, WireDoctorConditionSnapshot.Outcome> outcomes =
                    WireDoctorConditionSnapshot.extract(report);

            assertThat(outcomes).isNotEmpty();
            // Jackson autoconfig is on the classpath and applies → matched.
            assertThat(outcomes.keySet())
                    .anyMatch(name -> name.contains("JacksonAutoConfiguration"));
            assertThat(outcomes.values())
                    .extracting(WireDoctorConditionSnapshot.Outcome::outcome)
                    .contains("matched");
        });
    }

    @Test
    void notMatchedOutcomeCarriesReason() {
        runner.run(context -> {
            ConditionEvaluationReport report =
                    ConditionEvaluationReport.get(context.getSourceApplicationContext().getBeanFactory());
            Map<String, WireDoctorConditionSnapshot.Outcome> outcomes =
                    WireDoctorConditionSnapshot.extract(report);

            outcomes.values().stream()
                    .filter(o -> "notMatched".equals(o.outcome()))
                    .findFirst()
                    .ifPresent(o -> assertThat(o.reason()).isNotBlank());
        });
    }

    @Test
    void extractIsSortedByClassNameForDeterministicBaselines() {
        runner.run(context -> {
            ConditionEvaluationReport report =
                    ConditionEvaluationReport.get(context.getSourceApplicationContext().getBeanFactory());
            Map<String, WireDoctorConditionSnapshot.Outcome> outcomes =
                    WireDoctorConditionSnapshot.extract(report);

            assertThat(new java.util.ArrayList<>(outcomes.keySet())).isSorted();
        });
    }

    // ── outcome equality (the diff signal) ───────────────────────────────────

    @Test
    void outcomesWithDifferentReasonsAreEqual() {
        // Reason wording changes across Boot versions; only the outcome diffs.
        var a = new WireDoctorConditionSnapshot.Outcome("notMatched", "did not find class X");
        var b = new WireDoctorConditionSnapshot.Outcome("notMatched", "@ConditionalOnClass X absent");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void outcomesWithDifferentOutcomeAreNotEqual() {
        var matched = new WireDoctorConditionSnapshot.Outcome("matched", null);
        var notMatched = new WireDoctorConditionSnapshot.Outcome("notMatched", "x");
        assertThat(matched).isNotEqualTo(notMatched);
    }

    // ── serialization round-trip ─────────────────────────────────────────────

    @Test
    void toReportMapOmitsNullReason() {
        Map<String, WireDoctorConditionSnapshot.Outcome> outcomes = Map.of(
                "com.example.AAuto", new WireDoctorConditionSnapshot.Outcome("matched", null));
        Map<String, Object> map = WireDoctorConditionSnapshot.toReportMap(
                new java.util.TreeMap<>(outcomes));

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) map.get("com.example.AAuto");
        assertThat(entry).containsEntry("outcome", "matched").doesNotContainKey("reason");
    }

    @Test
    void jsonRoundTripPreservesOutcomesAndReasons() throws Exception {
        Map<String, WireDoctorConditionSnapshot.Outcome> outcomes = new java.util.TreeMap<>(Map.of(
                "com.example.AAuto", new WireDoctorConditionSnapshot.Outcome("matched", null),
                "com.example.BAuto", new WireDoctorConditionSnapshot.Outcome("notMatched", "no class B"),
                "com.example.CAuto", new WireDoctorConditionSnapshot.Outcome("excluded", null)));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(
                mapper.writeValueAsString(WireDoctorConditionSnapshot.toReportMap(outcomes)));
        Map<String, WireDoctorConditionSnapshot.Outcome> back =
                WireDoctorConditionSnapshot.fromJson(node);

        assertThat(back).hasSize(3);
        assertThat(back.get("com.example.AAuto").outcome()).isEqualTo("matched");
        assertThat(back.get("com.example.BAuto").outcome()).isEqualTo("notMatched");
        assertThat(back.get("com.example.BAuto").reason()).isEqualTo("no class B");
        assertThat(back.get("com.example.CAuto").outcome()).isEqualTo("excluded");
    }

    // ── backward compatibility contract ──────────────────────────────────────

    @Test
    void missingConditionsSectionParsesToNullNotEmpty() throws Exception {
        // v0.2.0-era baselines carry no conditions — null (skip diff) must be
        // distinguishable from empty (report present, zero autoconfigs).
        JsonNode oldBaseline = new ObjectMapper().readTree("{\"dependencies\": {}}");
        assertThat(WireDoctorConditionSnapshot.fromJson(oldBaseline.path("conditions")))
                .isNull();
        assertThat(WireDoctorConditionSnapshot.fromJson(null)).isNull();
    }

    @Test
    void malformedConditionEntriesAreSkippedNotFatal() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"good\": {\"outcome\": \"matched\"}, \"bad\": {\"noOutcomeKey\": true}}");
        Map<String, WireDoctorConditionSnapshot.Outcome> outcomes =
                WireDoctorConditionSnapshot.fromJson(node);

        assertThat(outcomes).containsOnlyKeys("good");
    }
}
