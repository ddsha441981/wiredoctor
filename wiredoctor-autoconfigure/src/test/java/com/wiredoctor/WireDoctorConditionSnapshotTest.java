/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorConditionSnapshot} (v0.5.0).
 * <p>
 * Extraction runs against a real {@link ConditionEvaluationReport} produced by
 * an {@link ApplicationContextRunner} — not a mock — so the test breaks if a
 * Boot upgrade changes the report API semantics we depend on.
 * <p>
 * The fixture uses only WireDoctor's own autoconfiguration plus a local
 * {@code @ConditionalOnProperty} config: Spring Boot's autoconfig classes move
 * between packages across major versions (e.g. Jackson in Boot 4), so relying
 * on a specific Boot autoconfig would break the compat matrix (2.7 → 4.x).
 * {@code @ConditionalOnProperty} is a {@code SpringBootCondition}, so its
 * outcomes are recorded in the report exactly like an autoconfig's.
 */
class WireDoctorConditionSnapshotTest {

    /**
     * Local condition fixture: one bean whose condition matches by default,
     * one that never matches — giving deterministic {@code matched} +
     * {@code notMatched} outcomes independent of the Boot version.
     */
    @Configuration(proxyBeanMethods = false)
    static class ConditionFixture {
        @Bean
        @ConditionalOnProperty(name = "wd.fixture.on", havingValue = "true", matchIfMissing = true)
        String matchedBean() {
            return "on";
        }

        @Bean
        @ConditionalOnProperty(name = "wd.fixture.absent", havingValue = "true")
        String notMatchedBean() {
            return "off";
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    WireDoctorAutoConfiguration.class))
            .withUserConfiguration(ConditionFixture.class);

    // ── extract ──────────────────────────────────────────────────────────────

    @Test
    void nullReportYieldsEmptySnapshot() {
        assertThat(WireDoctorConditionSnapshot.extract(null)).isEmpty();
    }

    @Test
    void extractCapturesMatchedAndNotMatchedOutcomes() {
        runner.run(context -> {
            Map<String, WireDoctorConditionSnapshot.Outcome> outcomes = extract(context);

            assertThat(outcomes).isNotEmpty();
            // Our fixture guarantees both a matched and a notMatched outcome,
            // regardless of the Boot version under test.
            assertThat(outcomes.values())
                    .extracting(WireDoctorConditionSnapshot.Outcome::outcome)
                    .contains("matched", "notMatched");
            assertThat(outcomes.keySet()).anyMatch(name -> name.contains("matchedBean"));
            assertThat(outcomes.keySet()).anyMatch(name -> name.contains("notMatchedBean"));
        });
    }

    @Test
    void notMatchedOutcomeCarriesReason() {
        runner.run(context -> {
            Map<String, WireDoctorConditionSnapshot.Outcome> outcomes = extract(context);

            outcomes.values().stream()
                    .filter(o -> "notMatched".equals(o.outcome()))
                    .findFirst()
                    .ifPresent(o -> assertThat(o.reason()).isNotBlank());
        });
    }

    @Test
    void extractIsSortedByClassNameForDeterministicBaselines() {
        runner.run(context -> {
            Map<String, WireDoctorConditionSnapshot.Outcome> outcomes = extract(context);
            assertThat(new java.util.ArrayList<>(outcomes.keySet())).isSorted();
        });
    }

    private static Map<String, WireDoctorConditionSnapshot.Outcome> extract(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        ConditionEvaluationReport report = ConditionEvaluationReport.get(
                context.getSourceApplicationContext().getBeanFactory());
        return WireDoctorConditionSnapshot.extract(report);
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
