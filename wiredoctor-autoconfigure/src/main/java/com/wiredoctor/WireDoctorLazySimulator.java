/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suggests {@code @Lazy} bean placements to break dependency cycles.
 * <p>
 * Given the dependency graph and the cycles detected by {@link CycleDetector}
 * (each cycle is a strongly connected component's member list), this simulator
 * identifies which beans, if marked {@code @Lazy}, would break those cycles.
 * <p>
 * <strong>Key insight:</strong> in Spring, marking bean B as {@code @Lazy}
 * makes all <em>incoming</em> edges to B lazy — B is injected as a proxy and
 * only instantiated on first use. Every member of an SCC has at least one
 * incoming edge from within the SCC, so lazying any member breaks the eager
 * instantiation cycle. Suggestions are ranked by:
 * <ol>
 *   <li>Number of cycles broken (descending)</li>
 *   <li>Downstream impact / fan-in (ascending — prefer minimal blast radius)</li>
 * </ol>
 * Pure computation over data the analyzer already has; never touches the
 * {@code BeanFactory} — consistent with the zero-intrusion guarantees.
 *
 * @author Deendayal Kumawat
 * @since 0.3.0
 */
public final class WireDoctorLazySimulator {

    private WireDoctorLazySimulator() {
        // Static utility
    }

    /**
     * Analyze cycles and generate ranked {@code @Lazy} placement suggestions.
     *
     * @param graph  dependency graph: bean name → names it depends on
     *               (same shape {@link CycleDetector#detectCycles} consumes)
     * @param cycles detected cycles, where each cycle is the member list of a
     *               strongly connected component (no repeated closing element)
     * @return ranked list of suggestions; empty if no cycles detected
     */
    public static List<LazySuggestion> suggestLazyPlacements(Map<String, String[]> graph,
                                                             List<List<String>> cycles) {
        if (cycles == null || cycles.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Integer> fanInCounts = calculateFanIn(graph);

        // Map: beanName -> set of cycle indices this bean can break.
        // Every SCC member is a candidate: lazying it severs its incoming
        // in-cycle edge, so eager construction no longer loops.
        Map<String, Set<Integer>> beanToCycles = new HashMap<>();
        for (int cycleIndex = 0; cycleIndex < cycles.size(); cycleIndex++) {
            for (String bean : cycles.get(cycleIndex)) {
                beanToCycles.computeIfAbsent(bean, k -> new HashSet<>()).add(cycleIndex);
            }
        }

        List<LazySuggestion> suggestions = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> entry : beanToCycles.entrySet()) {
            String beanName = entry.getKey();
            List<Integer> breaksCycles = new ArrayList<>(entry.getValue());
            Collections.sort(breaksCycles);

            suggestions.add(new LazySuggestion(
                    beanName,
                    breaksCycles,
                    fanInCounts.getOrDefault(beanName, 0)));
        }

        // Rank: most cycles broken first, then lowest downstream impact,
        // then name for a deterministic report.
        suggestions.sort(Comparator
                .comparingInt((LazySuggestion s) -> -s.breaksCycles.size())
                .thenComparingInt(s -> s.downstreamImpact)
                .thenComparing(s -> s.beanName));

        return suggestions;
    }

    /**
     * Serializes suggestions into the JSON report shape.
     *
     * @param suggestions ranked suggestions from {@link #suggestLazyPlacements}
     * @return list of ordered maps ready for Jackson serialization
     */
    public static List<Map<String, Object>> toReportList(List<LazySuggestion> suggestions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LazySuggestion s : suggestions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("beanName",         s.beanName);
            entry.put("breaksCycles",     s.breaksCycles);
            entry.put("downstreamImpact", s.downstreamImpact);
            out.add(entry);
        }
        return out;
    }

    /**
     * Calculate fan-in (number of incoming dependency edges) for each bean.
     */
    private static Map<String, Integer> calculateFanIn(Map<String, String[]> graph) {
        Map<String, Integer> fanIn = new HashMap<>();
        if (graph == null) return fanIn;

        for (String[] dependencies : graph.values()) {
            for (String dep : dependencies) {
                fanIn.merge(dep, 1, Integer::sum);
            }
        }
        return fanIn;
    }

    /**
     * A suggestion to mark a specific bean as {@code @Lazy}.
     */
    public static final class LazySuggestion {
        /** Bean name to mark lazy. */
        public final String beanName;

        /** Indices (into the report's {@code cycles} array) this suggestion would break. */
        public final List<Integer> breaksCycles;

        /** Number of beans that depend on this bean (fan-in / blast radius). */
        public final int downstreamImpact;

        LazySuggestion(String beanName, List<Integer> breaksCycles, int downstreamImpact) {
            this.beanName = beanName;
            this.breaksCycles = breaksCycles;
            this.downstreamImpact = downstreamImpact;
        }
    }
}
