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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CycleDetector}.
 * <p>
 * Covers self-loops, simple and nested SCCs, disconnected/empty graphs, and a
 * 10k-node deep chain that guards the iterative (non-recursive) Tarjan
 * implementation against {@link StackOverflowError}.
 */
class CycleDetectorTest {

    @Test
    void emptyGraphHasNoCycles() {
        assertThat(CycleDetector.detectCycles(Map.of())).isEmpty();
    }

    @Test
    void acyclicGraphHasNoCycles() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"c"},
                "c", new String[]{}
        );
        assertThat(CycleDetector.detectCycles(graph)).isEmpty();
    }

    @Test
    void selfLoopIsReportedAsCycle() {
        Map<String, String[]> graph = Map.of("a", new String[]{"a"});
        List<List<String>> cycles = CycleDetector.detectCycles(graph);
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactly("a");
    }

    @Test
    void twoNodeCycleIsDetected() {
        Map<String, String[]> graph = Map.of(
                "alphaBean", new String[]{"betaBean"},
                "betaBean", new String[]{"alphaBean"}
        );
        List<List<String>> cycles = CycleDetector.detectCycles(graph);
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactlyInAnyOrder("alphaBean", "betaBean");
    }

    @Test
    void multipleIndependentCyclesAreAllDetected() {
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"a"},
                "x", new String[]{"y"},
                "y", new String[]{"z"},
                "z", new String[]{"x"},
                "lonely", new String[]{}
        );
        List<List<String>> cycles = CycleDetector.detectCycles(graph);
        assertThat(cycles).hasSize(2);
        assertThat(cycles).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrder("a", "b"));
        assertThat(cycles).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrder("x", "y", "z"));
    }

    @Test
    void nestedSccIsReportedAsOneComponent() {
        // a→b→c→a plus inner shortcut b→a: still ONE strongly connected component.
        Map<String, String[]> graph = Map.of(
                "a", new String[]{"b"},
                "b", new String[]{"c", "a"},
                "c", new String[]{"a"}
        );
        List<List<String>> cycles = CycleDetector.detectCycles(graph);
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void cycleReachableOnlyThroughAcyclicPrefixIsDetected() {
        // entry → a → b → a (the cycle does not include the entry node)
        Map<String, String[]> graph = Map.of(
                "entry", new String[]{"a"},
                "a", new String[]{"b"},
                "b", new String[]{"a"}
        );
        List<List<String>> cycles = CycleDetector.detectCycles(graph);
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void edgesToNodesMissingFromKeySetDoNotBreakDetection() {
        // "b" appears only as a dependency, never as a key (framework beans often do this).
        Map<String, String[]> graph = Map.of("a", new String[]{"b"});
        assertThat(CycleDetector.detectCycles(graph)).isEmpty();
    }

    @Test
    void disconnectedGraphComponentsAreAllVisited() {
        Map<String, String[]> graph = Map.of(
                "i1", new String[]{},
                "i2", new String[]{},
                "c1", new String[]{"c2"},
                "c2", new String[]{"c1"}
        );
        List<List<String>> cycles = CycleDetector.detectCycles(graph);
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    void tenThousandNodeDeepChainDoesNotOverflowStack() {
        // Linear chain n0 → n1 → ... → n9999: recursion-based Tarjan would blow the
        // JVM call stack here; the iterative rewrite must handle it and find no cycles.
        int n = 10_000;
        Map<String, String[]> graph = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            graph.put("n" + i, new String[]{"n" + (i + 1)});
        }
        graph.put("n" + (n - 1), new String[0]);

        assertThat(CycleDetector.detectCycles(graph)).isEmpty();
    }

    @Test
    void tenThousandNodeDeepCycleIsDetectedWithoutOverflow() {
        // Same deep chain but closed into one giant cycle: n9999 → n0.
        int n = 10_000;
        Map<String, String[]> graph = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            graph.put("n" + i, new String[]{"n" + (i + 1)});
        }
        graph.put("n" + (n - 1), new String[]{"n0"});

        List<List<String>> cycles = CycleDetector.detectCycles(graph);
        assertThat(cycles).hasSize(1);
        assertThat(Set.copyOf(cycles.get(0))).hasSize(n);
    }
}
