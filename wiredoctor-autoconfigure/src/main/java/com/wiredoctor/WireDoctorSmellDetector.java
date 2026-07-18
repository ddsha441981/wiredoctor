/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes classic architecture-health metrics over the runtime-resolved bean
 * dependency graph.
 * <p>
 * Static tools (ArchUnit, JDepend, Structure101) compute these from bytecode
 * and package structure; WireDoctor computes them on the <em>live, resolved</em>
 * graph — reflecting what Spring actually wired (proxies, conditional beans,
 * active profiles), not what the source declares.
 * <p>
 * Metrics (all pure graph functions, zero heuristics):
 * <ul>
 *   <li><strong>Fan-in hotspots</strong> — beans with the most dependents
 *       (coupling hotspots / God Object smell)</li>
 *   <li><strong>Fan-out hotspots</strong> — beans with the most dependencies
 *       (Shotgun Surgery smell)</li>
 *   <li><strong>Instability</strong> — Martin's metric
 *       {@code I = Ce / (Ca + Ce)} per bean, where Ca = afferent (fan-in) and
 *       Ce = efferent (fan-out). I=0 is maximally stable, I=1 maximally
 *       unstable. Beans above the threshold are refactor-risk candidates.</li>
 * </ul>
 * Never touches the {@code BeanFactory} — consistent with the zero-intrusion
 * guarantees.
 *
 * @author Deendayal Kumawat
 * @since 0.3.0
 */
public final class WireDoctorSmellDetector {

    /** How many hotspots to report per direction. */
    static final int TOP_N = 10;

    /** Instability threshold above which a bean is flagged as a refactor candidate. */
    static final double INSTABILITY_THRESHOLD = 0.8;

    private WireDoctorSmellDetector() {
        // Static utility
    }

    /** Fan-in and fan-out degree counts for the whole graph, computed once. */
    public static final class Degrees {
        final Map<String, Integer> fanIn;
        final Map<String, Integer> fanOut;

        Degrees(Map<String, Integer> fanIn, Map<String, Integer> fanOut) {
            this.fanIn = fanIn;
            this.fanOut = fanOut;
        }
    }

    /**
     * Compute fan-in/fan-out for every bean appearing in the graph
     * (as a key or as a dependency).
     *
     * @param graph dependency graph: bean name → names it depends on
     * @return degree counts, shared by all metric computations
     */
    public static Degrees computeDegrees(Map<String, String[]> graph) {
        Map<String, Integer> fanIn = new HashMap<>();
        Map<String, Integer> fanOut = new HashMap<>();
        if (graph == null) {
            return new Degrees(fanIn, fanOut);
        }
        for (Map.Entry<String, String[]> entry : graph.entrySet()) {
            fanOut.merge(entry.getKey(), entry.getValue().length, Integer::sum);
            for (String dep : entry.getValue()) {
                fanIn.merge(dep, 1, Integer::sum);
            }
        }
        return new Degrees(fanIn, fanOut);
    }

    /**
     * Top-{@value TOP_N} beans by fan-in (most depended-on).
     *
     * @param graph   dependency graph
     * @param degrees precomputed degrees from {@link #computeDegrees}
     * @return report entries {@code {beanName, inDegree, dependents}}, highest first
     */
    public static List<Map<String, Object>> highFanIn(Map<String, String[]> graph, Degrees degrees) {
        List<Map<String, Object>> out = new ArrayList<>();
        degrees.fanIn.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_N)
                .forEach(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("beanName", e.getKey());
                    entry.put("inDegree", e.getValue());
                    entry.put("dependents", dependentsOf(graph, e.getKey()));
                    out.add(entry);
                });
        return out;
    }

    /**
     * Top-{@value TOP_N} beans by fan-out (most dependencies).
     *
     * @param graph   dependency graph
     * @param degrees precomputed degrees from {@link #computeDegrees}
     * @return report entries {@code {beanName, outDegree, dependencies}}, highest first
     */
    public static List<Map<String, Object>> highFanOut(Map<String, String[]> graph, Degrees degrees) {
        List<Map<String, Object>> out = new ArrayList<>();
        degrees.fanOut.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_N)
                .forEach(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("beanName", e.getKey());
                    entry.put("outDegree", e.getValue());
                    entry.put("dependencies", Arrays.asList(graph.get(e.getKey())));
                    out.add(entry);
                });
        return out;
    }

    /**
     * Beans with instability {@code I = Ce / (Ca + Ce)} at or above
     * {@value INSTABILITY_THRESHOLD}, ranked most unstable first.
     * <p>
     * Beans with zero fan-in AND non-trivial fan-out dominate this list by
     * construction (I=1.0). That is the correct signal — nothing shields them
     * from their dependencies' changes — but to keep the list meaningful only
     * beans with {@code fanOut >= 2} are considered (a leaf bean depending on
     * one thing is not a smell).
     *
     * @param degrees precomputed degrees from {@link #computeDegrees}
     * @return report entries {@code {beanName, instability, fanIn, fanOut}}
     */
    public static List<Map<String, Object>> unstable(Degrees degrees) {
        List<Map<String, Object>> out = new ArrayList<>();
        degrees.fanOut.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(e -> {
                    int ce = e.getValue();
                    int ca = degrees.fanIn.getOrDefault(e.getKey(), 0);
                    double instability = (double) ce / (ca + ce);
                    return Map.entry(e.getKey(), new double[]{instability, ca, ce});
                })
                .filter(e -> e.getValue()[0] >= INSTABILITY_THRESHOLD)
                .sorted(Comparator.<Map.Entry<String, double[]>>comparingDouble(e -> -e.getValue()[0])
                        .thenComparing(e -> -e.getValue()[2])
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_N)
                .forEach(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("beanName",    e.getKey());
                    entry.put("instability", Math.round(e.getValue()[0] * 100.0) / 100.0);
                    entry.put("fanIn",       (int) e.getValue()[1]);
                    entry.put("fanOut",      (int) e.getValue()[2]);
                    out.add(entry);
                });
        return out;
    }

    /**
     * Full {@code smells} report section.
     *
     * @param graph dependency graph
     * @return {@code {highFanIn: [...], highFanOut: [...], unstable: [...], fanIn: {bean: n}}}
     */
    public static Map<String, Object> toReportMap(Map<String, String[]> graph) {
        Degrees degrees = computeDegrees(graph);
        Map<String, Object> smells = new LinkedHashMap<>();
        smells.put("highFanIn",  highFanIn(graph, degrees));
        smells.put("highFanOut", highFanOut(graph, degrees));
        smells.put("unstable",   unstable(degrees));
        // Full fan-in map: consumed by the HTML report to size graph nodes.
        smells.put("fanIn",      new LinkedHashMap<>(degrees.fanIn));
        return smells;
    }

    private static List<String> dependentsOf(Map<String, String[]> graph, String bean) {
        List<String> dependents = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : graph.entrySet()) {
            for (String dep : entry.getValue()) {
                if (bean.equals(dep)) {
                    dependents.add(entry.getKey());
                    break;
                }
            }
        }
        dependents.sort(Comparator.naturalOrder());
        return dependents;
    }
}
