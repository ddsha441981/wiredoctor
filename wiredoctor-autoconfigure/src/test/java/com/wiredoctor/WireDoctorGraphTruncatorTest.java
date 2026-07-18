/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorGraphTruncator}.
 * <p>
 * Covers the no-op paths (unlimited, under cap), top-N-by-fan-in selection,
 * unconditional retention of cycle participants, edge filtering to surviving
 * endpoints, and deterministic tie-breaking.
 */
class WireDoctorGraphTruncatorTest {

    private static Map<String, Integer> fanInOf(Map<String, String[]> graph) {
        return WireDoctorSmellDetector.computeDegrees(graph).fanIn;
    }

    @Test
    void zeroCapMeansUnlimited() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{}
        );
        WireDoctorGraphTruncator.Result result =
                WireDoctorGraphTruncator.truncate(graph, List.of(), fanInOf(graph), 0);
        assertThat(result.truncated).isFalse();
        assertThat(result.graph).isSameAs(graph);
    }

    @Test
    void graphUnderCapIsReturnedUntouched() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{}
        );
        WireDoctorGraphTruncator.Result result =
                WireDoctorGraphTruncator.truncate(graph, List.of(), fanInOf(graph), 2000);
        assertThat(result.truncated).isFalse();
        assertThat(result.graph).isSameAs(graph);
        assertThat(result.originalNodeCount).isEqualTo(2);
        assertThat(result.keptNodeCount).isEqualTo(2);
    }

    @Test
    void keepsTopNodesByFanIn() {
        // hub has fan-in 3, mid 1, the leaves 0.
        Map<String, String[]> graph = new HashMap<>();
        graph.put("hub", new String[]{});
        graph.put("mid", new String[]{"hub"});
        graph.put("leaf1", new String[]{"hub", "mid"});
        graph.put("leaf2", new String[]{"hub"});

        WireDoctorGraphTruncator.Result result =
                WireDoctorGraphTruncator.truncate(graph, List.of(), fanInOf(graph), 2);

        assertThat(result.truncated).isTrue();
        assertThat(result.graph.keySet()).containsExactlyInAnyOrder("hub", "mid");
        assertThat(result.originalNodeCount).isEqualTo(4);
        assertThat(result.keptNodeCount).isEqualTo(2);
    }

    @Test
    void cycleParticipantsAreAlwaysKeptRegardlessOfFanIn() {
        // cycleA/cycleB have low fan-in but are in a cycle; hub1..hub3 have high fan-in.
        Map<String, String[]> graph = new HashMap<>();
        graph.put("cycleA", new String[]{"cycleB"});
        graph.put("cycleB", new String[]{"cycleA"});
        for (int i = 1; i <= 3; i++) {
            graph.put("hub" + i, new String[]{});
            for (int j = 0; j < 5; j++) {
                graph.put("user" + i + "_" + j, new String[]{"hub" + i});
            }
        }
        List<List<String>> cycles = List.of(List.of("cycleA", "cycleB"));

        WireDoctorGraphTruncator.Result result =
                WireDoctorGraphTruncator.truncate(graph, cycles, fanInOf(graph), 4);

        assertThat(result.truncated).isTrue();
        assertThat(result.graph.keySet()).contains("cycleA", "cycleB");
        // Cycle edges survive intact:
        assertThat(result.graph.get("cycleA")).containsExactly("cycleB");
        assertThat(result.graph.get("cycleB")).containsExactly("cycleA");
    }

    @Test
    void edgesToDroppedNodesAreRemoved() {
        Map<String, String[]> graph = new HashMap<>();
        graph.put("hub", new String[]{"dropped"});
        graph.put("keeper", new String[]{"hub", "dropped"});
        graph.put("dropped", new String[]{});
        graph.put("other", new String[]{"hub", "keeper"});

        // hub fan-in 2, keeper 1, dropped 2... make dropped lose on tiebreak? Give hub+keeper clear wins:
        graph.put("extra1", new String[]{"hub", "keeper"});
        graph.put("extra2", new String[]{"hub", "keeper"});

        WireDoctorGraphTruncator.Result result =
                WireDoctorGraphTruncator.truncate(graph, List.of(), fanInOf(graph), 2);

        assertThat(result.graph.keySet()).containsExactlyInAnyOrder("hub", "keeper");
        // keeper's edge to hub survives; its edge to "dropped" does not:
        assertThat(result.graph.get("keeper")).containsExactly("hub");
        assertThat(result.graph.get("hub")).isEmpty();
    }

    @Test
    void fanInTiesBreakAlphabeticallyForDeterministicReports() {
        Map<String, String[]> graph = new HashMap<>();
        graph.put("zeta", new String[]{});
        graph.put("alpha", new String[]{});
        graph.put("mike", new String[]{});

        WireDoctorGraphTruncator.Result result =
                WireDoctorGraphTruncator.truncate(graph, List.of(), fanInOf(graph), 2);

        assertThat(result.graph.keySet()).containsExactlyInAnyOrder("alpha", "mike");
    }

    @Test
    void cycleMembersNotInGraphKeySetAreIgnored() {
        // Defensive: a cycle member that is not a graph key must not break the cut.
        Map<String, String[]> graph = new HashMap<>();
        graph.put("a", new String[]{});
        graph.put("b", new String[]{});
        graph.put("c", new String[]{});

        WireDoctorGraphTruncator.Result result = WireDoctorGraphTruncator.truncate(
                graph, List.of(List.of("ghost")), fanInOf(graph), 2);

        assertThat(result.truncated).isTrue();
        assertThat(result.keptNodeCount).isEqualTo(2);
        assertThat(result.graph.keySet()).doesNotContain("ghost");
    }
}
