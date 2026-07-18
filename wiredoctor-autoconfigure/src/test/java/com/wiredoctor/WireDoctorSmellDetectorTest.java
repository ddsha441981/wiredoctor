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
 * Unit tests for {@link WireDoctorSmellDetector}.
 * <p>
 * Covers degree computation (including beans that appear only as
 * dependencies), hotspot ranking and top-N capping, Martin's instability
 * metric with its fan-out floor, deterministic tie-breaking, and the full
 * report-map shape consumed by the JSON report and HTML node sizing.
 */
class WireDoctorSmellDetectorTest {

    @Test
    void emptyGraphYieldsEmptyMetrics() {
        Map<String, Object> smells = WireDoctorSmellDetector.toReportMap(Map.of());
        assertThat(smells.get("highFanIn")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(smells.get("highFanOut")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(smells.get("unstable")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(smells.get("fanIn")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
    }

    @Test
    void degreesCountBeansAppearingOnlyAsDependencies() {
        // "hub" is never a key — framework beans often appear this way.
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"hub"},
                "b", new String[]{"hub"}
        );
        WireDoctorSmellDetector.Degrees d = WireDoctorSmellDetector.computeDegrees(graph);
        assertThat(d.fanIn).containsEntry("hub", 2);
        assertThat(d.fanOut).containsEntry("a", 1).containsEntry("b", 1);
        assertThat(d.fanOut).doesNotContainKey("hub");
    }

    @Test
    void highFanInRanksHubsFirstWithDependentsListed() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"hub", "mid"},
                "b", new String[]{"hub"},
                "c", new String[]{"hub"},
                "d", new String[]{"mid"}
        );
        List<Map<String, Object>> hotspots = WireDoctorSmellDetector.highFanIn(
                graph, WireDoctorSmellDetector.computeDegrees(graph));

        assertThat(hotspots.get(0).get("beanName")).isEqualTo("hub");
        assertThat(hotspots.get(0).get("inDegree")).isEqualTo(3);
        assertThat((List<String>) hotspots.get(0).get("dependents"))
                .containsExactly("a", "b", "c");
        assertThat(hotspots.get(1).get("beanName")).isEqualTo("mid");
        assertThat(hotspots.get(1).get("inDegree")).isEqualTo(2);
    }

    @Test
    void highFanOutRanksWidestDependersFirst() {
        Map<String, String[]> graph = Map.of(
                "wide", new String[]{"x", "y", "z"},
                "narrow", new String[]{"x"},
                "x", new String[]{},
                "y", new String[]{},
                "z", new String[]{}
        );
        List<Map<String, Object>> hotspots = WireDoctorSmellDetector.highFanOut(
                graph, WireDoctorSmellDetector.computeDegrees(graph));

        assertThat(hotspots).hasSize(2); // zero-fan-out beans excluded
        assertThat(hotspots.get(0).get("beanName")).isEqualTo("wide");
        assertThat(hotspots.get(0).get("outDegree")).isEqualTo(3);
        assertThat((List<String>) hotspots.get(0).get("dependencies"))
                .containsExactlyInAnyOrder("x", "y", "z");
    }

    @Test
    void hotspotListsAreCappedAtTopN() {
        // 15 beans each depending on their own hub → 15 fan-out entries.
        Map<String, String[]> graph = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            graph.put("bean" + i, new String[]{"dep" + i, "shared"});
        }
        WireDoctorSmellDetector.Degrees d = WireDoctorSmellDetector.computeDegrees(graph);
        assertThat(WireDoctorSmellDetector.highFanOut(graph, d))
                .hasSize(WireDoctorSmellDetector.TOP_N);
        assertThat(WireDoctorSmellDetector.highFanIn(graph, d))
                .hasSizeLessThanOrEqualTo(WireDoctorSmellDetector.TOP_N);
    }

    @Test
    void instabilityFlagsBeansAboveThreshold() {
        // "volatile": fan-out 4, fan-in 0 → I = 1.0 (flagged).
        // "balanced": fan-out 2, fan-in 3 → I = 0.4 (not flagged).
        // "stable": fan-out 0 → excluded by fan-out floor.
        Map<String, String[]> graph = Map.of(
                "volatile", new String[]{"w", "x", "y", "z"},
                "balanced", new String[]{"w", "x"},
                "p1", new String[]{"balanced"},
                "p2", new String[]{"balanced"},
                "p3", new String[]{"balanced"}
        );
        List<Map<String, Object>> unstable = WireDoctorSmellDetector.unstable(
                WireDoctorSmellDetector.computeDegrees(graph));

        assertThat(unstable).hasSize(1);
        assertThat(unstable.get(0).get("beanName")).isEqualTo("volatile");
        assertThat(unstable.get(0).get("instability")).isEqualTo(1.0);
        assertThat(unstable.get(0).get("fanIn")).isEqualTo(0);
        assertThat(unstable.get(0).get("fanOut")).isEqualTo(4);
    }

    @Test
    void instabilityIgnoresSingleDependencyLeaves() {
        // fan-out 1, fan-in 0 → I=1.0 but below the fanOut >= 2 floor: not a smell.
        Map<String, String[]> graph = Map.of("leaf", new String[]{"x"});
        assertThat(WireDoctorSmellDetector.unstable(
                WireDoctorSmellDetector.computeDegrees(graph))).isEmpty();
    }

    @Test
    void instabilityAtExactThresholdIsIncluded() {
        // fan-out 4, fan-in 1 → I = 4/5 = 0.8 exactly.
        Map<String, String[]> graph = Map.of(
                "edge", new String[]{"w", "x", "y", "z"},
                "p", new String[]{"edge"}
        );
        List<Map<String, Object>> unstable = WireDoctorSmellDetector.unstable(
                WireDoctorSmellDetector.computeDegrees(graph));
        assertThat(unstable).hasSize(1);
        assertThat(unstable.get(0).get("instability")).isEqualTo(0.8);
    }

    @Test
    void rankingTiesBreakDeterministicallyByBeanName() {
        Map<String, String[]> graph = Map.of(
                "z", new String[]{"t1"},
                "a", new String[]{"t2"},
                "m", new String[]{"t1"},
                "n", new String[]{"t2"}
        );
        // t1 and t2 both have fan-in 2 → alphabetical.
        List<Map<String, Object>> hotspots = WireDoctorSmellDetector.highFanIn(
                graph, WireDoctorSmellDetector.computeDegrees(graph));
        assertThat(hotspots).extracting(e -> e.get("beanName"))
                .containsExactly("t1", "t2");
    }

    @Test
    void reportMapExposesFullFanInForHtmlNodeSizing() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"hub"},
                "b", new String[]{"hub"}
        );
        Map<String, Object> smells = WireDoctorSmellDetector.toReportMap(graph);
        Map<String, Integer> fanIn = (Map<String, Integer>) smells.get("fanIn");
        assertThat(fanIn).containsEntry("hub", 2);
        assertThat(smells).containsOnlyKeys(
                "highFanIn", "highFanOut", "unstable", "frameworkFiltered", "fanIn");
        assertThat(smells.get("frameworkFiltered")).isEqualTo(false);
    }

    // ── v0.4.0: user-bean filtering of the ranked lists ──────────────────────

    @Test
    void filteredRankingsExcludeFrameworkBeansButKeepUserBeans() {
        // "fw" is a heavily depended-on framework bean; "svc" is a user bean.
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"fw", "svc"},
                "b", new String[]{"fw"},
                "c", new String[]{"fw"}
        );
        List<Map<String, Object>> hotspots = WireDoctorSmellDetector.highFanIn(
                graph, WireDoctorSmellDetector.computeDegrees(graph),
                name -> !name.equals("fw"));

        assertThat(hotspots).extracting(e -> e.get("beanName"))
                .containsExactly("svc")
                .doesNotContain("fw");
    }

    @Test
    void filteredReportMapKeepsFullFanInMapForNodeSizing() {
        // Framework "fw" is filtered out of the ranked lists, but its true
        // fan-in must still appear in the fanIn map (HTML sizes every node).
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"fw"},
                "b", new String[]{"fw"}
        );
        Map<String, Object> smells = WireDoctorSmellDetector.toReportMap(
                graph, name -> !name.equals("fw"), true);

        assertThat((List<Map<String, Object>>) smells.get("highFanIn")).isEmpty();
        assertThat(smells.get("frameworkFiltered")).isEqualTo(true);
        Map<String, Integer> fanIn = (Map<String, Integer>) smells.get("fanIn");
        assertThat(fanIn).containsEntry("fw", 2); // full weight preserved
    }

    @Test
    void filteredUnstableExcludesFrameworkBeans() {
        // Both "fwEdge" and "userEdge" are unstable (I=1.0), but only the user
        // bean should survive filtering.
        Map<String, String[]> graph = Map.of(
                "fwEdge",   new String[]{"w", "x", "y"},
                "userEdge", new String[]{"m", "n", "o"}
        );
        List<Map<String, Object>> unstable = WireDoctorSmellDetector.unstable(
                WireDoctorSmellDetector.computeDegrees(graph),
                name -> !name.startsWith("fw"));

        assertThat(unstable).extracting(e -> e.get("beanName"))
                .containsExactly("userEdge");
    }
}
