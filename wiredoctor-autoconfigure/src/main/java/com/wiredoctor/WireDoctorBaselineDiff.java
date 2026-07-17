/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure, Spring-free diff engine for the Architectural Regression Guard.
 * <p>
 * Compares a committed baseline snapshot of the bean graph against the current
 * run and reports what changed: beans added/removed, dependency edges
 * added/removed, and — the hard signal — cycles that exist now but not in the
 * baseline. No I/O, no Spring types; fully unit-testable.
 * <p>
 * Honesty note: the diff is only as stable as bean names. Renamed or
 * generated bean names show up as a remove + add pair, not a rename.
 *
 * @author Deendayal Kumawat
 * @since 0.2.0
 */
public final class WireDoctorBaselineDiff {

    private WireDoctorBaselineDiff() {
        // static utility
    }

    /**
     * Minimal structural snapshot of a bean graph: the inputs the diff needs,
     * decoupled from how the report was produced (live map vs parsed JSON).
     */
    public static final class Snapshot {
        private final Set<String> beans;
        private final Map<String, Set<String>> edges;
        private final List<Set<String>> cycles;

        Snapshot(Set<String> beans, Map<String, Set<String>> edges, List<Set<String>> cycles) {
            this.beans = beans;
            this.edges = edges;
            this.cycles = cycles;
        }

        /**
         * Builds a snapshot from the live report structure assembled by
         * {@link WireDoctorAnalyzer} (the {@code dependencies} section).
         *
         * @param graph  bean name → dependency names
         * @param cycles detected cycles, each a list of bean names
         * @return the snapshot
         */
        public static Snapshot fromAnalysis(Map<String, String[]> graph, List<List<String>> cycles) {
            Map<String, Set<String>> edges = new LinkedHashMap<>();
            graph.forEach((bean, deps) -> edges.put(bean, new LinkedHashSet<>(List.of(deps))));
            List<Set<String>> cycleSets = new ArrayList<>();
            cycles.forEach(c -> cycleSets.add(new LinkedHashSet<>(c)));
            return new Snapshot(new LinkedHashSet<>(graph.keySet()), edges, cycleSets);
        }

        /**
         * Builds a snapshot from a parsed {@code wiredoctor-report.json} /
         * baseline file ({@code dependencies.graph} + {@code dependencies.cycles}).
         *
         * @param reportRoot the parsed report root node
         * @return the snapshot
         */
        public static Snapshot fromJson(JsonNode reportRoot) {
            JsonNode deps = reportRoot.path("dependencies");
            Map<String, Set<String>> edges = new LinkedHashMap<>();
            deps.path("graph").fields().forEachRemaining(entry -> {
                Set<String> targets = new LinkedHashSet<>();
                entry.getValue().forEach(t -> targets.add(t.asText()));
                edges.put(entry.getKey(), targets);
            });
            List<Set<String>> cycles = new ArrayList<>();
            deps.path("cycles").forEach(cycle -> {
                Set<String> beansInCycle = new LinkedHashSet<>();
                cycle.forEach(b -> beansInCycle.add(b.asText()));
                cycles.add(beansInCycle);
            });
            return new Snapshot(new LinkedHashSet<>(edges.keySet()), edges, cycles);
        }

        public Set<String> beans() { return beans; }
        public Map<String, Set<String>> edges() { return edges; }
        public List<Set<String>> cycles() { return cycles; }
    }

    /**
     * Result of a baseline diff. Collections are sorted for deterministic
     * report output.
     */
    public static final class DiffResult {
        private final Set<String> addedBeans;
        private final Set<String> removedBeans;
        private final Set<String> addedEdges;
        private final Set<String> removedEdges;
        private final List<Set<String>> newCycles;
        private final List<Set<String>> resolvedCycles;

        DiffResult(Set<String> addedBeans, Set<String> removedBeans,
                   Set<String> addedEdges, Set<String> removedEdges,
                   List<Set<String>> newCycles, List<Set<String>> resolvedCycles) {
            this.addedBeans = addedBeans;
            this.removedBeans = removedBeans;
            this.addedEdges = addedEdges;
            this.removedEdges = removedEdges;
            this.newCycles = newCycles;
            this.resolvedCycles = resolvedCycles;
        }

        public Set<String> addedBeans() { return addedBeans; }
        public Set<String> removedBeans() { return removedBeans; }
        /** Added edges in {@code "from -> to"} form. */
        public Set<String> addedEdges() { return addedEdges; }
        /** Removed edges in {@code "from -> to"} form. */
        public Set<String> removedEdges() { return removedEdges; }
        /** Cycles present now but absent from the baseline — the regression signal. */
        public List<Set<String>> newCycles() { return newCycles; }
        /** Cycles present in the baseline but gone now — improvements, reported for symmetry. */
        public List<Set<String>> resolvedCycles() { return resolvedCycles; }

        /** @return {@code true} when nothing changed at all */
        public boolean isEmpty() {
            return addedBeans.isEmpty() && removedBeans.isEmpty()
                    && addedEdges.isEmpty() && removedEdges.isEmpty()
                    && newCycles.isEmpty() && resolvedCycles.isEmpty();
        }

        /** @return {@code true} when at least one new cycle was introduced */
        public boolean hasNewCycles() {
            return !newCycles.isEmpty();
        }

        /**
         * Serializes the diff into a plain map for Jackson (report-friendly shape).
         *
         * @return an ordered map mirroring the {@code wiredoctor-diff.json} schema
         */
        public Map<String, Object> toReportMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("addedBeansCount", addedBeans.size());
            map.put("addedBeans", new ArrayList<>(addedBeans));
            map.put("removedBeansCount", removedBeans.size());
            map.put("removedBeans", new ArrayList<>(removedBeans));
            map.put("addedEdgesCount", addedEdges.size());
            map.put("addedEdges", new ArrayList<>(addedEdges));
            map.put("removedEdgesCount", removedEdges.size());
            map.put("removedEdges", new ArrayList<>(removedEdges));
            map.put("newCyclesCount", newCycles.size());
            map.put("newCycles", cyclesAsLists(newCycles));
            map.put("resolvedCyclesCount", resolvedCycles.size());
            map.put("resolvedCycles", cyclesAsLists(resolvedCycles));
            return map;
        }

        private static List<List<String>> cyclesAsLists(List<Set<String>> cycles) {
            List<List<String>> out = new ArrayList<>();
            cycles.forEach(c -> out.add(new ArrayList<>(c)));
            return out;
        }
    }

    /**
     * Computes the structural diff between a baseline snapshot and the current run.
     * <p>
     * A cycle counts as "new" when no baseline cycle covers the same set of
     * beans exactly — so a baseline cycle that grew by one bean is reported as
     * new (honest: the architecture did change for the worse).
     *
     * @param baseline the committed baseline snapshot
     * @param current  the snapshot of the current run
     * @return the diff result
     */
    public static DiffResult diff(Snapshot baseline, Snapshot current) {
        Set<String> addedBeans = new TreeSet<>(current.beans());
        addedBeans.removeAll(baseline.beans());
        Set<String> removedBeans = new TreeSet<>(baseline.beans());
        removedBeans.removeAll(current.beans());

        Set<String> baselineEdges = flattenEdges(baseline.edges());
        Set<String> currentEdges = flattenEdges(current.edges());
        Set<String> addedEdges = new TreeSet<>(currentEdges);
        addedEdges.removeAll(baselineEdges);
        Set<String> removedEdges = new TreeSet<>(baselineEdges);
        removedEdges.removeAll(currentEdges);

        Set<Set<String>> baselineCycles = new HashSet<>(baseline.cycles());
        Set<Set<String>> currentCycles = new HashSet<>(current.cycles());
        List<Set<String>> newCycles = new ArrayList<>();
        for (Set<String> cycle : current.cycles()) {
            if (!baselineCycles.contains(cycle)) newCycles.add(cycle);
        }
        List<Set<String>> resolvedCycles = new ArrayList<>();
        for (Set<String> cycle : baseline.cycles()) {
            if (!currentCycles.contains(cycle)) resolvedCycles.add(cycle);
        }

        return new DiffResult(
                Collections.unmodifiableSet(addedBeans),
                Collections.unmodifiableSet(removedBeans),
                Collections.unmodifiableSet(addedEdges),
                Collections.unmodifiableSet(removedEdges),
                Collections.unmodifiableList(newCycles),
                Collections.unmodifiableList(resolvedCycles));
    }

    private static Set<String> flattenEdges(Map<String, Set<String>> edges) {
        Set<String> flat = new HashSet<>();
        edges.forEach((from, targets) -> targets.forEach(to -> flat.add(from + " -> " + to)));
        return flat;
    }
}
