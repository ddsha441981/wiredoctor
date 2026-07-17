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
                "resolvedCyclesCount", "resolvedCycles");
        assertThat(map.get("newCyclesCount")).isEqualTo(1);
        assertThat((List<String>) map.get("addedBeans")).containsExactly("b");
    }

    @Test
    void emptySnapshotsDiffClean() {
        var empty = snapshot(Map.of(), List.of());
        assertThat(WireDoctorBaselineDiff.diff(empty, empty).isEmpty()).isTrue();
    }
}
