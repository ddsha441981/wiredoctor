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
 * Unit tests for the pure {@link WireDoctorCriticalPath} computation:
 * chain vs parallel-branch weighting, SCC condensation, missing timings,
 * deep-graph iteration safety, and the report/render shapes.
 */
class WireDoctorCriticalPathTest {

    @Test
    void emptyGraphYieldsEmptyPath() {
        assertThat(WireDoctorCriticalPath.compute(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void noTimedBeansYieldsEmptyPath() {
        Map<String, String[]> graph = Map.of("a", new String[]{"b"}, "b", new String[0]);
        assertThat(WireDoctorCriticalPath.compute(graph, Map.of())).isEmpty();
    }

    @Test
    void singleChainIsReturnedDependencyFirst() {
        // a depends on b, b depends on c → instantiation order c, b, a.
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"c"},
                "c", new String[0]);
        Map<String, Long> times = Map.of("a", 100L, "b", 50L, "c", 200L);

        List<WireDoctorCriticalPath.PathNode> path = WireDoctorCriticalPath.compute(graph, times);

        assertThat(path).extracting(WireDoctorCriticalPath.PathNode::beanName)
                .containsExactly("c", "b", "a");
        assertThat(path.get(2).cumulativeMs()).isEqualTo(350L);
    }

    @Test
    void heavierParallelBranchWins() {
        // root depends on two branches: fast (10ms) and slow (300ms).
        Map<String, String[]> graph = Map.of(
                "root", new String[]{"fast", "slow"},
                "fast", new String[0],
                "slow", new String[0]);
        Map<String, Long> times = Map.of("root", 20L, "fast", 10L, "slow", 300L);

        List<WireDoctorCriticalPath.PathNode> path = WireDoctorCriticalPath.compute(graph, times);

        assertThat(path).extracting(WireDoctorCriticalPath.PathNode::beanName)
                .containsExactly("slow", "root");
        assertThat(path.get(1).cumulativeMs()).isEqualTo(320L);
    }

    @Test
    void oneGatingBeanBeatsTwoParallelHeavyBeans() {
        // The motivating case: two 200ms beans in parallel branches do NOT add up,
        // one 250ms bean gating another does.
        Map<String, String[]> graph = Map.of(
                "parallelUser1", new String[]{"heavy1"},
                "parallelUser2", new String[]{"heavy2"},
                "heavy1", new String[0],
                "heavy2", new String[0],
                "chainTop", new String[]{"gate"},
                "gate", new String[0]);
        Map<String, Long> times = Map.of(
                "heavy1", 200L, "heavy2", 200L,
                "parallelUser1", 1L, "parallelUser2", 1L,
                "gate", 250L, "chainTop", 60L);

        List<WireDoctorCriticalPath.PathNode> path = WireDoctorCriticalPath.compute(graph, times);

        assertThat(path).extracting(WireDoctorCriticalPath.PathNode::beanName)
                .containsExactly("gate", "chainTop");
        assertThat(path.get(1).cumulativeMs()).isEqualTo(310L);
    }

    @Test
    void untimedBeanStillLinksThePathWithZeroWeight() {
        // b has no timing but structurally links a → c.
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"c"},
                "c", new String[0]);
        Map<String, Long> times = Map.of("a", 100L, "c", 200L);

        List<WireDoctorCriticalPath.PathNode> path = WireDoctorCriticalPath.compute(graph, times);

        assertThat(path).extracting(WireDoctorCriticalPath.PathNode::beanName)
                .containsExactly("c", "b", "a");
        assertThat(path.get(1).ownMs()).isZero();
        assertThat(path.get(2).cumulativeMs()).isEqualTo(300L);
    }

    @Test
    void cycleIsCondensedIntoOneNodeWithSummedWeight() {
        // x ↔ y cycle (60 + 40 = 100ms) feeding into top (50ms).
        Map<String, String[]> graph = Map.of(
                "top", new String[]{"x"},
                "x", new String[]{"y"},
                "y", new String[]{"x"});
        Map<String, Long> times = Map.of("top", 50L, "x", 60L, "y", 40L);

        List<WireDoctorCriticalPath.PathNode> path = WireDoctorCriticalPath.compute(graph, times);

        assertThat(path).hasSize(2);
        assertThat(path.get(0).beanName()).startsWith("[cycle:");
        assertThat(path.get(0).beanName()).contains("x").contains("y");
        assertThat(path.get(0).ownMs()).isEqualTo(100L);
        assertThat(path.get(1).cumulativeMs()).isEqualTo(150L);
    }

    @Test
    void edgesToUnknownBeansAreIgnored() {
        Map<String, String[]> graph = Map.of("a", new String[]{"ghostBean"});
        Map<String, Long> times = Map.of("a", 100L);

        List<WireDoctorCriticalPath.PathNode> path = WireDoctorCriticalPath.compute(graph, times);

        assertThat(path).extracting(WireDoctorCriticalPath.PathNode::beanName)
                .containsExactly("a");
    }

    @Test
    void tenThousandNodeChainComputesIteratively() {
        // Deep chain guards against any recursive implementation sneaking back in.
        int n = 10_000;
        Map<String, String[]> graph = new HashMap<>();
        Map<String, Long> times = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            graph.put("n" + i, new String[]{"n" + (i + 1)});
            times.put("n" + i, 1L);
        }
        graph.put("n" + (n - 1), new String[0]);
        times.put("n" + (n - 1), 1L);

        List<WireDoctorCriticalPath.PathNode> path = WireDoctorCriticalPath.compute(graph, times);

        assertThat(path).hasSize(n);
        assertThat(path.get(n - 1).cumulativeMs()).isEqualTo(n);
        assertThat(path.get(0).beanName()).isEqualTo("n" + (n - 1)); // deepest dependency first
    }

    @Test
    void reportMapCarriesPercentAndDisclaimer() {
        Map<String, String[]> graph = Map.of("a", new String[]{"b"}, "b", new String[0]);
        Map<String, Long> times = Map.of("a", 100L, "b", 200L);
        var path = WireDoctorCriticalPath.compute(graph, times);

        Map<String, Object> section = WireDoctorCriticalPath.toReportMap(path, 600L);

        assertThat(section.get("available")).isEqualTo(true);
        assertThat(section.get("totalMs")).isEqualTo(300L);
        assertThat(section.get("percentOfReadiness")).isEqualTo(50.0);
        assertThat(section.get("disclaimer").toString()).contains("approximation");
        assertThat((List<?>) section.get("path")).hasSize(2);
    }

    @Test
    void reportMapForEmptyPathSaysUnavailable() {
        Map<String, Object> section = WireDoctorCriticalPath.toReportMap(List.of(), 0L);
        assertThat(section.get("available")).isEqualTo(false);
        assertThat(section.get("totalMs")).isEqualTo(0L);
        assertThat(section).doesNotContainKey("percentOfReadiness");
    }

    @Test
    void renderJoinsWithArrows() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[0]);
        Map<String, Long> times = Map.of("a", 1L, "b", 2L);
        var path = WireDoctorCriticalPath.compute(graph, times);
        assertThat(WireDoctorCriticalPath.render(path)).isEqualTo("b -> a");
        assertThat(WireDoctorCriticalPath.render(List.of())).isEmpty();
    }
}
