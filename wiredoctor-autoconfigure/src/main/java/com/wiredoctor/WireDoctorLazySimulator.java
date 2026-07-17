package com.wiredoctor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Suggests {@code @Lazy} bean placements to break dependency cycles.
 * <p>
 * Given a set of detected cycles, this simulator identifies which beans,
 * if marked lazy, would break those cycles. Suggestions are ranked by:
 * <ol>
 *   <li>Number of cycles broken (descending)</li>
 *   <li>Downstream impact / fan-in (ascending — prefer minimal blast radius)</li>
 * </ol>
 * <p>
 * <strong>Algorithm:</strong> For each edge (A → B) in a cycle, marking B as
 * {@code @Lazy} breaks that edge (Spring loads B on first access, not at A's
 * construction time). Aggregate suggestions by target bean, then rank.
 *
 * @since 0.3.0
 */
public class WireDoctorLazySimulator {

    /**
     * Analyze cycles and generate ranked {@code @Lazy} placement suggestions.
     *
     * @param graph  dependency graph containing nodes and edges (from report)
     * @param cycles list of cycles, where each cycle is a list of bean names
     *               forming a path that returns to the first bean
     * @return ranked list of suggestions; empty if no cycles detected
     */
    public static List<LazySuggestion> suggestLazyPlacements(Map<String, Object> graph,
                                                              List<List<String>> cycles) {
        if (cycles == null || cycles.isEmpty()) {
            return Collections.emptyList();
        }

        // Extract edges from graph for fan-in calculation
        @SuppressWarnings("unchecked")
        List<Map<String, String>> edges = (List<Map<String, String>>) graph.get("edges");
        Map<String, Integer> fanInCounts = calculateFanIn(edges);

        // Map: beanName -> set of cycle indices this bean can break
        Map<String, Set<Integer>> beanToCycles = new HashMap<>();

        for (int cycleIndex = 0; cycleIndex < cycles.size(); cycleIndex++) {
            List<String> cycle = cycles.get(cycleIndex);

            // Extract edges from cycle: [A, B, C, A] → edges (A→B), (B→C), (C→A)
            for (int i = 0; i < cycle.size() - 1; i++) {
                String target = cycle.get(i + 1);

                // Skip the closing edge (last element back to first) if it's a duplicate
                if (i == cycle.size() - 2 && target.equals(cycle.get(0))) {
                    continue; // Already counted this edge
                }

                beanToCycles.computeIfAbsent(target, k -> new HashSet<>()).add(cycleIndex);
            }
        }

        // Build suggestions
        List<LazySuggestion> suggestions = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> entry : beanToCycles.entrySet()) {
            String beanName = entry.getKey();
            List<Integer> breaksCycles = new ArrayList<>(entry.getValue());
            Collections.sort(breaksCycles);

            int downstreamImpact = fanInCounts.getOrDefault(beanName, 0);

            suggestions.add(new LazySuggestion(
                beanName,
                breaksCycles,
                downstreamImpact,
                false,  // onCriticalPath — phase 2
                null    // estimatedTimeSavedMs — phase 2
            ));
        }

        // Rank: most cycles broken first, then lowest downstream impact
        suggestions.sort(Comparator
            .comparingInt((LazySuggestion s) -> -s.breaksCycles.size())
            .thenComparingInt(s -> s.downstreamImpact));

        return suggestions;
    }

    /**
     * Calculate fan-in (number of incoming edges) for each bean.
     */
    private static Map<String, Integer> calculateFanIn(List<Map<String, String>> edges) {
        Map<String, Integer> fanIn = new HashMap<>();
        if (edges == null) return fanIn;

        for (Map<String, String> edge : edges) {
            String to = edge.get("to");
            if (to != null) {
                fanIn.merge(to, 1, Integer::sum);
            }
        }
        return fanIn;
    }

    /**
     * A suggestion to mark a specific bean as {@code @Lazy}.
     */
    public static class LazySuggestion {
        /** Bean name to mark lazy. */
        public final String beanName;

        /** Indices of cycles this suggestion would break. */
        public final List<Integer> breaksCycles;

        /** Number of beans that depend on this bean (fan-in / blast radius). */
        public final int downstreamImpact;

        /** Whether this bean is on the startup critical path (phase 2 feature). */
        public final boolean onCriticalPath;

        /** Estimated startup time saved if made lazy (phase 2 feature). */
        public final Long estimatedTimeSavedMs;

        public LazySuggestion(String beanName,
                              List<Integer> breaksCycles,
                              int downstreamImpact,
                              boolean onCriticalPath,
                              Long estimatedTimeSavedMs) {
            this.beanName = beanName;
            this.breaksCycles = breaksCycles;
            this.downstreamImpact = downstreamImpact;
            this.onCriticalPath = onCriticalPath;
            this.estimatedTimeSavedMs = estimatedTimeSavedMs;
        }

        @Override
        public String toString() {
            return String.format("Make '%s' @Lazy (breaks %d cycle(s), impacts %d bean(s))",
                beanName, breaksCycles.size(), downstreamImpact);
        }
    }
}
