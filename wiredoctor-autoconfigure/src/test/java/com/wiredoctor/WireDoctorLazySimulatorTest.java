/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorLazySimulator}.
 * <p>
 * Cycles are fed in the same shape {@link CycleDetector} produces: each cycle
 * is the member list of a strongly connected component, with no repeated
 * closing element. Covers ranking by cycles broken and by fan-in blast radius,
 * overlapping multi-cycle graphs, self-loops, and the empty/no-cycle cases.
 */
class WireDoctorLazySimulatorTest {

    @Test
    void noCyclesYieldsEmptySuggestions() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"c"},
                "c", new String[]{}
        );
        assertThat(WireDoctorLazySimulator.suggestLazyPlacements(graph, List.of())).isEmpty();
        assertThat(WireDoctorLazySimulator.suggestLazyPlacements(graph, null)).isEmpty();
    }

    @Test
    void simpleCycleSuggestsEveryMember() {
        // A → B → C → A: lazying any member breaks the cycle.
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"c"},
                "c", new String[]{"a"}
        );
        List<List<String>> cycles = List.of(List.of("a", "b", "c"));

        List<WireDoctorLazySimulator.LazySuggestion> suggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);

        assertThat(suggestions).hasSize(3);
        assertThat(suggestions).extracting(s -> s.beanName)
                .containsExactlyInAnyOrder("a", "b", "c");
        assertThat(suggestions).allSatisfy(s ->
                assertThat(s.breaksCycles).containsExactly(0));
    }

    @Test
    void ranksByLowestFanInWhenCyclesBrokenAreEqual() {
        // Cycle a → b → c → a; "b" additionally depended on by x and y (fan-in 3),
        // "a" by z (fan-in 2), "c" only from within the cycle (fan-in 1).
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"c"},
                "c", new String[]{"a"},
                "x", new String[]{"b"},
                "y", new String[]{"b"},
                "z", new String[]{"a"}
        );
        List<List<String>> cycles = List.of(List.of("a", "b", "c"));

        List<WireDoctorLazySimulator.LazySuggestion> suggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);

        assertThat(suggestions).extracting(s -> s.beanName)
                .containsExactly("c", "a", "b");
        assertThat(suggestions).extracting(s -> s.downstreamImpact)
                .containsExactly(1, 2, 3);
    }

    @Test
    void beanBreakingMoreCyclesRanksFirstDespiteHigherFanIn() {
        // Cycle 0: a ↔ b. Cycle 1: b ↔ c (separate SCC reports would merge these,
        // but the simulator must handle whatever cycle list it is given).
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"a", "c"},
                "c", new String[]{"b"}
        );
        List<List<String>> cycles = List.of(
                List.of("a", "b"),
                List.of("b", "c")
        );

        List<WireDoctorLazySimulator.LazySuggestion> suggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);

        assertThat(suggestions.get(0).beanName).isEqualTo("b");
        assertThat(suggestions.get(0).breaksCycles).containsExactly(0, 1);
        assertThat(suggestions).extracting(s -> s.beanName)
                .containsExactly("b", "a", "c");
    }

    @Test
    void selfLoopIsSuggested() {
        Map<String, String[]> graph = Map.of("a", new String[]{"a"});
        List<List<String>> cycles = List.of(List.of("a"));

        List<WireDoctorLazySimulator.LazySuggestion> suggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).beanName).isEqualTo("a");
        assertThat(suggestions.get(0).breaksCycles).containsExactly(0);
        assertThat(suggestions.get(0).downstreamImpact).isEqualTo(1);
    }

    @Test
    void multipleIndependentCyclesGetIndependentSuggestions() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"a"},
                "x", new String[]{"y"},
                "y", new String[]{"x"}
        );
        List<List<String>> cycles = List.of(
                List.of("a", "b"),
                List.of("x", "y")
        );

        List<WireDoctorLazySimulator.LazySuggestion> suggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);

        assertThat(suggestions).hasSize(4);
        assertThat(suggestions).filteredOn(s -> s.breaksCycles.equals(List.of(0)))
                .extracting(s -> s.beanName).containsExactlyInAnyOrder("a", "b");
        assertThat(suggestions).filteredOn(s -> s.breaksCycles.equals(List.of(1)))
                .extracting(s -> s.beanName).containsExactlyInAnyOrder("x", "y");
    }

    @Test
    void tieBreaksDeterministicallyByBeanName() {
        // a ↔ b, identical cycles broken and identical fan-in (1 each).
        Map<String, String[]> graph = Map.of(
                "b", new String[]{"a"},
                "a", new String[]{"b"}
        );
        List<List<String>> cycles = List.of(List.of("b", "a"));

        List<WireDoctorLazySimulator.LazySuggestion> suggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);

        assertThat(suggestions).extracting(s -> s.beanName).containsExactly("a", "b");
    }

    @Test
    void toReportListPreservesOrderAndShape() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"a"}
        );
        List<List<String>> cycles = List.of(List.of("a", "b"));

        List<Map<String, Object>> report = WireDoctorLazySimulator.toReportList(
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles));

        assertThat(report).hasSize(2);
        assertThat(report.get(0)).containsOnlyKeys("beanName", "breaksCycles", "downstreamImpact");
        assertThat(report.get(0).get("beanName")).isEqualTo("a");
    }
}
