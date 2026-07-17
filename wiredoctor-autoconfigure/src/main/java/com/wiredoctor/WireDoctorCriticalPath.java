/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, Spring-free computation of the startup critical path: the longest
 * instantiation-weighted dependency chain through the bean graph.
 * <p>
 * The flat "top-20 slowest beans" list says what was slow, not what actually
 * gated readiness — two 200ms beans in parallel branches don't add up, while
 * one 200ms bean blocking five others does. This joins the dependency graph
 * with per-bean instantiation timings and finds the heaviest chain.
 * <p>
 * Cycles are handled by condensing strongly connected components (via
 * {@link CycleDetector}'s SCC output) into single nodes whose weight is the
 * sum of their members, then running longest-path over the resulting DAG in
 * topological order — O(V+E), fully iterative.
 * <p>
 * Honesty note: instantiation time is an approximation of gating time
 * (parallel init and background threads aren't modeled). This is the
 * "instantiation-weighted critical path" — the right place to LOOK first,
 * not a wall-clock guarantee.
 *
 * @author Deendayal Kumawat
 * @since 0.2.0
 */
public final class WireDoctorCriticalPath {

    private WireDoctorCriticalPath() {
        // static utility
    }

    /**
     * One bean on the critical path with its own instantiation time and the
     * cumulative time up to and including it.
     *
     * @param beanName     the bean (or {@code "[cycle: a, b]"} for a condensed SCC)
     * @param ownMs        this node's instantiation milliseconds (0 when unknown)
     * @param cumulativeMs total milliseconds along the path up to here
     */
    public record PathNode(String beanName, long ownMs, long cumulativeMs) {
    }

    /**
     * Computes the longest instantiation-weighted path through the graph.
     * <p>
     * Beans without a timing entry get weight 0 — they can still appear on
     * the path as structural links but contribute no time.
     *
     * @param graph     bean name → dependency names (edges point at what the bean needs first)
     * @param beanTimes bean name → instantiation milliseconds (missing = 0)
     * @return the critical path ordered dependency-first (what had to finish
     *         earliest comes first); empty when the graph is empty or no bean
     *         carries any weight
     */
    public static List<PathNode> compute(Map<String, String[]> graph, Map<String, Long> beanTimes) {
        if (graph.isEmpty()) {
            return List.of();
        }

        // ── 1. Condense SCCs into super-nodes so the graph becomes a DAG ────
        List<List<String>> sccs = CycleDetector.detectCycles(graph);
        Map<String, Integer> beanToComponent = new HashMap<>();
        List<List<String>> components = new ArrayList<>();

        for (List<String> scc : sccs) {
            int id = components.size();
            components.add(scc);
            scc.forEach(bean -> beanToComponent.put(bean, id));
        }
        for (String bean : graph.keySet()) {
            if (!beanToComponent.containsKey(bean)) {
                int id = components.size();
                components.add(List.of(bean));
                beanToComponent.put(bean, id);
            }
        }

        int n = components.size();
        long[] weight = new long[n];
        for (int i = 0; i < n; i++) {
            long sum = 0;
            for (String bean : components.get(i)) {
                sum += beanTimes.getOrDefault(bean, 0L);
            }
            weight[i] = sum;
        }

        // Condensed edges: component of bean → component of each dependency.
        // Dependencies must exist BEFORE the dependent, so the path runs dep-first.
        List<List<Integer>> depEdges = new ArrayList<>(n);
        int[] pendingDeps = new int[n]; // number of (condensed) dependencies not yet processed
        for (int i = 0; i < n; i++) depEdges.add(new ArrayList<>());

        Map<Long, Boolean> seenEdge = new HashMap<>();
        graph.forEach((bean, deps) -> {
            int from = beanToComponent.get(bean);
            for (String dep : deps) {
                Integer to = beanToComponent.get(dep);
                if (to == null || to == from) continue; // unknown bean or intra-SCC edge
                long key = ((long) from << 32) | (to & 0xffffffffL);
                if (seenEdge.putIfAbsent(key, Boolean.TRUE) == null) {
                    depEdges.get(from).add(to);
                    pendingDeps[from]++;
                }
            }
        });

        // Reverse adjacency: dependency → dependents (for propagation).
        List<List<Integer>> dependents = new ArrayList<>(n);
        for (int i = 0; i < n; i++) dependents.add(new ArrayList<>());
        for (int from = 0; from < n; from++) {
            for (int to : depEdges.get(from)) {
                dependents.get(to).add(from);
            }
        }

        // ── 2. Longest path over the DAG, dependencies first (Kahn order) ───
        long[] best = new long[n];   // heaviest cumulative time ending at component i
        int[] bestPrev = new int[n]; // predecessor component on that path
        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            best[i] = weight[i];
            bestPrev[i] = -1;
            if (pendingDeps[i] == 0) ready.add(i);
        }

        while (!ready.isEmpty()) {
            int dep = ready.poll();
            for (int dependent : dependents.get(dep)) {
                long candidate = best[dep] + weight[dependent];
                if (candidate > best[dependent]) {
                    best[dependent] = candidate;
                    bestPrev[dependent] = dep;
                }
                if (--pendingDeps[dependent] == 0) {
                    ready.add(dependent);
                }
            }
        }

        // ── 3. Reconstruct the heaviest chain ────────────────────────────────
        int end = 0;
        for (int i = 1; i < n; i++) {
            if (best[i] > best[end]) end = i;
        }
        if (best[end] == 0) {
            return List.of(); // no bean carried any weight — nothing to say
        }

        List<Integer> chain = new ArrayList<>();
        for (int cur = end; cur != -1; cur = bestPrev[cur]) {
            chain.add(cur);
        }
        Collections.reverse(chain); // dependency-first order

        List<PathNode> path = new ArrayList<>(chain.size());
        long cumulative = 0;
        for (int component : chain) {
            cumulative += weight[component];
            path.add(new PathNode(label(components.get(component)), weight[component], cumulative));
        }
        return path;
    }

    /**
     * Serializes a computed path into a report-friendly map.
     *
     * @param path             the computed critical path (may be empty)
     * @param totalReadinessMs total application readiness time for the
     *                         "% of readiness" figure; {@code <= 0} omits the percentage
     * @return ordered map for the {@code criticalPath} report section
     */
    public static Map<String, Object> toReportMap(List<PathNode> path, long totalReadinessMs) {
        Map<String, Object> section = new LinkedHashMap<>();
        long pathMs = path.isEmpty() ? 0 : path.get(path.size() - 1).cumulativeMs();
        section.put("available", !path.isEmpty());
        section.put("totalMs", pathMs);
        if (totalReadinessMs > 0) {
            section.put("percentOfReadiness",
                    Math.round(pathMs * 1000.0 / totalReadinessMs) / 10.0);
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PathNode node : path) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("beanName", node.beanName());
            entry.put("ownMs", node.ownMs());
            entry.put("cumulativeMs", node.cumulativeMs());
            nodes.add(entry);
        }
        section.put("path", nodes);
        section.put("disclaimer",
                "Instantiation-weighted approximation; parallel init and background threads are not modeled.");
        return section;
    }

    /**
     * Renders the path as a console-friendly {@code a -> b -> c} chain.
     *
     * @param path the computed path
     * @return the rendered chain, or an empty string for an empty path
     */
    public static String render(List<PathNode> path) {
        StringBuilder sb = new StringBuilder();
        for (PathNode node : path) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(node.beanName());
        }
        return sb.toString();
    }

    private static String label(List<String> component) {
        if (component.size() == 1) {
            return component.get(0);
        }
        return "[cycle: " + String.join(", ", component) + "]";
    }
}
