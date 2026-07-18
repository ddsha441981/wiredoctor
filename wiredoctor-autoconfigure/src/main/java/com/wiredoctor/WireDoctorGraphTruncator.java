/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caps the serialized dependency graph for large applications (5k+ beans)
 * so the JSON report stays reviewable and the HTML visualizer does not
 * freeze the browser.
 * <p>
 * <strong>Scope guarantee:</strong> analysis (cycles, smells, critical path,
 * baseline diff) always runs on the FULL graph. Only the {@code graph}
 * section serialized into the report is truncated — to the top-N beans by
 * fan-in, since the most-depended-on nodes carry the most architectural
 * signal. Edges are kept only when both endpoints survive the cut.
 * <p>
 * Beans that participate in a detected cycle are always retained regardless
 * of fan-in: hiding the one thing WireDoctor exists to show would be absurd.
 *
 * @author Deendayal Kumawat
 * @since 0.3.0
 */
public final class WireDoctorGraphTruncator {

    private WireDoctorGraphTruncator() {
        // Static utility
    }

    /** Truncation outcome: the (possibly reduced) graph plus honest metadata. */
    public static final class Result {
        /** Graph to serialize — the original instance when no truncation occurred. */
        public final Map<String, String[]> graph;
        /** Whether the cap was applied. */
        public final boolean truncated;
        /** Node count before truncation (graph keys). */
        public final int originalNodeCount;
        /** Node count after truncation. */
        public final int keptNodeCount;

        Result(Map<String, String[]> graph, boolean truncated,
               int originalNodeCount, int keptNodeCount) {
            this.graph = graph;
            this.truncated = truncated;
            this.originalNodeCount = originalNodeCount;
            this.keptNodeCount = keptNodeCount;
        }
    }

    /**
     * Truncate the graph to at most {@code maxNodes} keys, keeping the top-N
     * by fan-in plus every cycle participant.
     *
     * @param graph    full dependency graph: bean name → dependency names
     * @param cycles   detected cycles (members are always retained)
     * @param fanIn    fan-in counts from {@link WireDoctorSmellDetector#computeDegrees}
     * @param maxNodes cap; {@code 0} (or a cap not exceeded) returns the
     *                 original graph untouched
     * @return the truncation result
     */
    public static Result truncate(Map<String, String[]> graph,
                                  List<List<String>> cycles,
                                  Map<String, Integer> fanIn,
                                  int maxNodes) {
        int originalCount = graph.size();
        if (maxNodes <= 0 || originalCount <= maxNodes) {
            return new Result(graph, false, originalCount, originalCount);
        }

        // Cycle participants are non-negotiable.
        Set<String> kept = new HashSet<>();
        if (cycles != null) {
            cycles.forEach(kept::addAll);
        }
        kept.retainAll(graph.keySet()); // guard against non-key cycle members

        // Fill remaining slots with the highest-fan-in beans (name tiebreak
        // for deterministic reports).
        List<String> byFanIn = new ArrayList<>(graph.keySet());
        byFanIn.sort(Comparator
                .comparingInt((String b) -> -fanIn.getOrDefault(b, 0))
                .thenComparing(Comparator.naturalOrder()));
        for (String bean : byFanIn) {
            if (kept.size() >= maxNodes) break;
            kept.add(bean);
        }

        // Rebuild: only kept keys, only edges whose target is also kept.
        Map<String, String[]> truncated = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : graph.entrySet()) {
            if (!kept.contains(entry.getKey())) continue;
            List<String> deps = new ArrayList<>();
            for (String dep : entry.getValue()) {
                if (kept.contains(dep)) deps.add(dep);
            }
            truncated.put(entry.getKey(), deps.toArray(new String[0]));
        }
        return new Result(truncated, true, originalCount, truncated.size());
    }
}
