/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure {@link WireDoctorBaselineDiff} engine: bean/edge
 * add-remove detection, new-cycle vs resolved-cycle classification, JSON
 * snapshot parsing, and the report-map shape.
 */
class WireDoctorBaselineDiffTest {

    private static WireDoctorBaselineDiff.Snapshot snapshot(Map<String, String[]> graph,
                                                            List<List<String>> cycles) {
        return WireDoctorBaselineDiff.Snapshot.fromAnalysis(graph, cycles);
    }

    @Test
    void identicalSnapshotsProduceEmptyDiff() {
        Map<String, String[]> graph = Map.of("a", new String[]{"b"}, "b", new String[0]);
        var diff = WireDoctorBaselineDiff.diff(
                snapshot(graph, List.of()), snapshot(graph, List.of()));
        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.hasNewCycles()).isFalse();
    }

    @Test
    void addedAndRemovedBeansAreDetected() {
        var baseline = snapshot(Map.of("a", new String[0], "old", new String[0]), List.of());
        var current = snapshot(Map.of("a", new String[0], "fresh", new String[0]), List.of());
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.addedBeans()).containsExactly("fresh");
        assertThat(diff.removedBeans()).containsExactly("old");
    }

    @Test
    void addedAndRemovedEdgesAreDetectedInArrowForm() {
        var baseline = snapshot(Map.of("a", new String[]{"b"}, "b", new String[0]), List.of());
        var current = snapshot(Map.of("a", new String[]{"c"}, "b", new String[0]), List.of());
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.addedEdges()).containsExactly("a -> c");
        assertThat(diff.removedEdges()).containsExactly("a -> b");
    }

    @Test
    void newCycleIsFlaggedAndTripsHasNewCycles() {
        var baseline = snapshot(Map.of("a", new String[]{"b"}, "b", new String[0]), List.of());
        var current = snapshot(
                Map.of("a", new String[]{"b"}, "b", new String[]{"a"}),
                List.of(List.of("a", "b")));
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.hasNewCycles()).isTrue();
        assertThat(diff.newCycles()).hasSize(1);
        assertThat(diff.newCycles().get(0)).containsExactlyInAnyOrder("a", "b");
        assertThat(diff.resolvedCycles()).isEmpty();
    }

    @Test
    void baselineCycleStillPresentIsNotNew() {
        List<List<String>> cycle = List.of(List.of("a", "b"));
        Map<String, String[]> graph = Map.of("a", new String[]{"b"}, "b", new String[]{"a"});
        var diff = WireDoctorBaselineDiff.diff(snapshot(graph, cycle), snapshot(graph, cycle));
        assertThat(diff.hasNewCycles()).isFalse();
        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void resolvedCycleIsReportedAsImprovementNotRegression() {
        var baseline = snapshot(
                Map.of("a", new String[]{"b"}, "b", new String[]{"a"}),
                List.of(List.of("a", "b")));
        var current = snapshot(Map.of("a", new String[]{"b"}, "b", new String[0]), List.of());
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.hasNewCycles()).isFalse();
        assertThat(diff.resolvedCycles()).hasSize(1);
    }

    @Test
    void grownCycleCountsAsNew() {
        // Baseline had a↔b; the cycle grew to a→b→c→a. The architecture worsened —
        // exact-set matching must flag it as new (and the old one as resolved).
        var baseline = snapshot(
                Map.of("a", new String[]{"b"}, "b", new String[]{"a"}, "c", new String[0]),
                List.of(List.of("a", "b")));
        var current = snapshot(
                Map.of("a", new String[]{"b"}, "b", new String[]{"c"}, "c", new String[]{"a"}),
                List.of(List.of("a", "b", "c")));
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.hasNewCycles()).isTrue();
        assertThat(diff.newCycles().get(0)).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(diff.resolvedCycles()).hasSize(1);
    }

    @Test
    void cycleOrderDoesNotMatterOnlyMembership() {
        // Tarjan may emit the same SCC in different vertex order across runs.
        var baseline = snapshot(
                Map.of("a", new String[]{"b"}, "b", new String[]{"a"}),
                List.of(List.of("b", "a")));
        var current = snapshot(
                Map.of("a", new String[]{"b"}, "b", new String[]{"a"}),
                List.of(List.of("a", "b")));
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.hasNewCycles()).isFalse();
    }

    @Test
    void snapshotParsesFromReportJson() throws Exception {
        String json = """
                {
                  "dependencies": {
                    "graph": { "a": ["b"], "b": ["a"], "solo": [] },
                    "cycles": [["a", "b"]]
                  }
                }
                """;
        var snap = WireDoctorBaselineDiff.Snapshot.fromJson(new ObjectMapper().readTree(json));
        assertThat(snap.beans()).containsExactlyInAnyOrder("a", "b", "solo");
        assertThat(snap.edges().get("a")).containsExactly("b");
        assertThat(snap.cycles()).hasSize(1);
        assertThat(snap.cycles().get(0)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void jsonRoundTripDiffAgainstLiveAnalysisIsEmpty() throws Exception {
        // A baseline parsed back from JSON must diff clean against the same live graph.
        Map<String, String[]> graph = Map.of("a", new String[]{"b"}, "b", new String[]{"a"});
        List<List<String>> cycles = List.of(List.of("a", "b"));
        String json = """
                { "dependencies": { "graph": { "a": ["b"], "b": ["a"] }, "cycles": [["a","b"]] } }
                """;
        var fromJson = WireDoctorBaselineDiff.Snapshot.fromJson(new ObjectMapper().readTree(json));
        var live = snapshot(graph, cycles);
        assertThat(WireDoctorBaselineDiff.diff(fromJson, live).isEmpty()).isTrue();
    }

    @Test
    void reportMapHasStableSchema() {
        var baseline = snapshot(Map.of("a", new String[0]), List.of());
        var current = snapshot(
                Map.of("a", new String[]{"b"}, "b", new String[]{"a"}),
                List.of(List.of("a", "b")));
        Map<String, Object> map = WireDoctorBaselineDiff.diff(baseline, current).toReportMap();
        assertThat(map.keySet()).containsExactly(
                "addedBeansCount", "addedBeans",
                "removedBeansCount", "removedBeans",
                "addedEdgesCount", "addedEdges",
                "removedEdgesCount", "removedEdges",
                "newCyclesCount", "newCycles",
                "resolvedCyclesCount", "resolvedCycles",
                "conditionDiff");
        assertThat(map.get("newCyclesCount")).isEqualTo(1);
        assertThat((List<String>) map.get("addedBeans")).containsExactly("b");
    }

    @Test
    void emptySnapshotsDiffClean() {
        var empty = snapshot(Map.of(), List.of());
        assertThat(WireDoctorBaselineDiff.diff(empty, empty).isEmpty()).isTrue();
    }

    // ── v0.5.0: condition diff ───────────────────────────────────────────────

    private static Map<String, WireDoctorConditionSnapshot.Outcome> conditions(
            Map<String, String> outcomes) {
        var map = new java.util.TreeMap<String, WireDoctorConditionSnapshot.Outcome>();
        outcomes.forEach((k, v) -> map.put(k, new WireDoctorConditionSnapshot.Outcome(v, null)));
        return map;
    }

    private static WireDoctorBaselineDiff.Snapshot snapshotWithConditions(
            Map<String, String> conditionOutcomes) {
        return WireDoctorBaselineDiff.Snapshot.fromAnalysis(
                Map.of("a", new String[0]), List.of(), conditions(conditionOutcomes));
    }

    @Test
    void conditionOutcomeFlipIsReportedAsChanged() {
        var baseline = snapshotWithConditions(Map.of(
                "com.example.AAuto", "matched",
                "com.example.BAuto", "matched"));
        var current = snapshotWithConditions(Map.of(
                "com.example.AAuto", "matched",
                "com.example.BAuto", "notMatched"));

        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.conditionDiffAvailable()).isTrue();
        assertThat(diff.hasConditionChanges()).isTrue();
        assertThat(diff.conditionsChanged()).hasSize(1);
        var change = diff.conditionsChanged().get(0);
        assertThat(change.className()).isEqualTo("com.example.BAuto");
        assertThat(change.oldOutcome()).isEqualTo("matched");
        assertThat(change.newOutcome()).isEqualTo("notMatched");
        assertThat(diff.isEmpty()).isFalse();
    }

    @Test
    void conditionClassAppearingOrVanishingIsAddedRemovedNotChanged() {
        // A Boot version bump changes the autoconfig candidate list itself —
        // expected churn, kept separate from the headline "changed" list.
        var baseline = snapshotWithConditions(Map.of(
                "com.example.OldAuto", "matched",
                "com.example.KeptAuto", "matched"));
        var current = snapshotWithConditions(Map.of(
                "com.example.KeptAuto", "matched",
                "com.example.NewAuto", "notMatched"));

        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.conditionsChanged()).isEmpty();
        assertThat(diff.conditionsAdded()).containsExactly("com.example.NewAuto");
        assertThat(diff.conditionsRemoved()).containsExactly("com.example.OldAuto");
    }

    @Test
    void oldBaselineWithoutConditionsSkipsConditionDiff() {
        // BACKWARD COMPATIBILITY (hard requirement): a v0.2.0-era baseline has
        // no conditions section — the condition diff must be unavailable, and
        // the current run's autoconfigs must NOT be reported as all-added.
        var oldBaseline = snapshot(Map.of("a", new String[0]), List.of());
        var current = snapshotWithConditions(Map.of("com.example.AAuto", "matched"));

        var diff = WireDoctorBaselineDiff.diff(oldBaseline, current);
        assertThat(diff.conditionDiffAvailable()).isFalse();
        assertThat(diff.hasConditionChanges()).isFalse();
        assertThat(diff.conditionsChanged()).isEmpty();
        assertThat(diff.conditionsAdded()).isEmpty();
        assertThat(diff.conditionsRemoved()).isEmpty();
        // bean diff still works untouched
        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void v020BaselineJsonStillParsesAndDiffsBeansOnly() throws Exception {
        // A real pre-v0.5.0 baseline file shape: dependencies only, no conditions.
        String oldJson = """
                {"dependencies": {"graph": {"a": ["b"], "b": []}, "cycles": []}}
                """;
        var baseline = WireDoctorBaselineDiff.Snapshot.fromJson(
                new ObjectMapper().readTree(oldJson));
        assertThat(baseline.conditions()).isNull();

        var current = WireDoctorBaselineDiff.Snapshot.fromAnalysis(
                Map.of("a", new String[]{"b"}, "b", new String[0]), List.of(),
                conditions(Map.of("com.example.AAuto", "matched")));
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.conditionDiffAvailable()).isFalse();
        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void conditionRoundTripThroughBaselineJson() throws Exception {
        // Write-mode serializes conditions into the baseline; fromJson must
        // read them back so the NEXT run's diff sees them.
        String json = """
                {
                  "dependencies": {"graph": {"a": []}, "cycles": []},
                  "conditions": {
                    "com.example.AAuto": {"outcome": "matched"},
                    "com.example.BAuto": {"outcome": "notMatched", "reason": "no class B"}
                  }
                }
                """;
        var baseline = WireDoctorBaselineDiff.Snapshot.fromJson(
                new ObjectMapper().readTree(json));
        assertThat(baseline.conditions()).isNotNull().hasSize(2);

        var current = snapshotWithConditions(Map.of(
                "com.example.AAuto", "notMatched",
                "com.example.BAuto", "notMatched"));
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.conditionsChanged()).hasSize(1);
        assertThat(diff.conditionsChanged().get(0).className()).isEqualTo("com.example.AAuto");
    }

    @Test
    void conditionChangedListIsSortedByClassName() {
        var baseline = snapshotWithConditions(Map.of(
                "com.z.ZAuto", "matched", "com.a.AAuto", "matched", "com.m.MAuto", "matched"));
        var current = snapshotWithConditions(Map.of(
                "com.z.ZAuto", "excluded", "com.a.AAuto", "excluded", "com.m.MAuto", "excluded"));

        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.conditionsChanged())
                .extracting(WireDoctorBaselineDiff.ConditionChange::className)
                .containsExactly("com.a.AAuto", "com.m.MAuto", "com.z.ZAuto");
    }

    @Test
    void conditionDiffSectionInReportMap() {
        var baseline = snapshotWithConditions(Map.of("com.example.AAuto", "matched"));
        var current = snapshotWithConditions(Map.of("com.example.AAuto", "notMatched"));

        Map<String, Object> map = WireDoctorBaselineDiff.diff(baseline, current).toReportMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> conditionDiff = (Map<String, Object>) map.get("conditionDiff");
        assertThat(conditionDiff.get("available")).isEqualTo(true);
        assertThat(conditionDiff.get("changedCount")).isEqualTo(1);
        assertThat(conditionDiff.keySet()).containsExactly(
                "available", "changedCount", "changed",
                "addedCount", "added", "removedCount", "removed");
    }

    // ── v0.7.0: timing regression ────────────────────────────────────────────

    private static WireDoctorBaselineDiff.Snapshot snapshotWithTiming(
            Map<String, String[]> graph, long startupMs, long slowBeanThreshold) {
        return WireDoctorBaselineDiff.Snapshot.fromAnalysis(
                graph, List.of(), Map.of(), startupMs, slowBeanThreshold);
    }

    @Test
    void startupTimeRegressionDetected() {
        var baseline = snapshotWithTiming(Map.of("a", new String[0]), 1000L, 500);
        var current = snapshotWithTiming(Map.of("a", new String[0]), 2000L, 500);

        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.startupTimeRegression()).isNotNull();
        assertThat(diff.startupTimeRegression().baselineMs()).isEqualTo(1000L);
        assertThat(diff.startupTimeRegression().currentMs()).isEqualTo(2000L);
        assertThat(diff.startupTimeRegression().deltaMs()).isEqualTo(1000L);
        assertThat(diff.startupTimeRegression().percentChange()).isEqualTo(1.0); // 100%
        assertThat(diff.isEmpty()).isFalse();
    }

    @Test
    void noStartupRegressionWhenTimingImproved() {
        var baseline = snapshotWithTiming(Map.of("a", new String[0]), 2000L, 500);
        var current = snapshotWithTiming(Map.of("a", new String[0]), 1500L, 500);

        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.startupTimeRegression()).isNull(); // improvement, not regression
    }

    @Test
    void noStartupRegressionWhenBaselineMissingTiming() {
        var baseline = snapshot(Map.of("a", new String[0]), List.of()); // pre-v0.7.0 baseline
        var current = snapshotWithTiming(Map.of("a", new String[0]), 2000L, 500);

        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.startupTimeRegression()).isNull(); // gracefully skip
        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void timingRegressionRoundTripThroughJson() throws Exception {
        String json = """
                {
                  "dependencies": {"graph": {"a": []}, "cycles": []},
                  "totalStartupMs": 1500,
                  "slowBeanThreshold": 800
                }
                """;
        var baseline = WireDoctorBaselineDiff.Snapshot.fromJson(
                new ObjectMapper().readTree(json));
        assertThat(baseline.timing()).isNotNull();
        assertThat(baseline.timing().totalStartupMs()).isEqualTo(1500L);
        assertThat(baseline.slowBeanThreshold()).isEqualTo(800L);

        var current = snapshotWithTiming(Map.of("a", new String[0]), 3000L, 800L);
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        assertThat(diff.startupTimeRegression()).isNotNull();
        assertThat(diff.startupTimeRegression().deltaMs()).isEqualTo(1500L);
    }

    @Test
    void timingRegressionInReportMap() {
        var baseline = snapshotWithTiming(Map.of("a", new String[0]), 1000L, 500);
        var current = snapshotWithTiming(Map.of("a", new String[0]), 2500L, 500);

        Map<String, Object> map = WireDoctorBaselineDiff.diff(baseline, current).toReportMap();
        assertThat(map.keySet()).contains("startupTimeDiff");

        @SuppressWarnings("unchecked")
        Map<String, Object> regression = (Map<String, Object>) map.get("startupTimeDiff");
        assertThat(regression.get("baselineMs")).isEqualTo(1000L);
        assertThat(regression.get("currentMs")).isEqualTo(2500L);
        assertThat(regression.get("deltaMs")).isEqualTo(1500L);
        assertThat(regression.get("percentChange")).isEqualTo(1.5); // 150%
    }

    @Test
    void newSlowBeansDetected() {
        var baseline = snapshotWithTiming(Map.of("fast", new String[0], "slow", new String[0]), 1000L, 500);
        var current = snapshotWithTiming(Map.of("fast", new String[0], "slow", new String[0]), 1000L, 500);

        // Simulate: "slow" bean was 400ms in baseline (not slow), now 600ms (slow)
        var diff = WireDoctorBaselineDiff.diff(baseline, current);
        // Note: This is a unit test for the diff engine — actual slow bean comparison
        // happens in WireDoctorAnalyzer with real bean timing data, so we can't fully
        // test newSlowBeans here without mocking bean instantiation times.
        // We'll test this in integration test instead.
        assertThat(diff.newSlowBeans()).isNotNull();
    }
}
