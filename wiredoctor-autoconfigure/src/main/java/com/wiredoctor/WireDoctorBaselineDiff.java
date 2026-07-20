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
        // v0.5.0: autoconfig condition outcomes. null = snapshot predates
        // condition tracking (old baseline) → condition diff is skipped.
        private final Map<String, WireDoctorConditionSnapshot.Outcome> conditions;
        // v0.7.0: startup timing data. null = snapshot predates timing tracking
        // (pre-v0.7.0 baseline) → timing diff is skipped.
        private final TimingSnapshot timing;
        // v0.7.0: slow-bean threshold at baseline-write time. null = old baseline.
        private final Long slowBeanThreshold;

        Snapshot(Set<String> beans, Map<String, Set<String>> edges, List<Set<String>> cycles) {
            this(beans, edges, cycles, null, null, null);
        }

        Snapshot(Set<String> beans, Map<String, Set<String>> edges, List<Set<String>> cycles,
                 Map<String, WireDoctorConditionSnapshot.Outcome> conditions) {
            this(beans, edges, cycles, conditions, null, null);
        }

        Snapshot(Set<String> beans, Map<String, Set<String>> edges, List<Set<String>> cycles,
                 Map<String, WireDoctorConditionSnapshot.Outcome> conditions,
                 TimingSnapshot timing, Long slowBeanThreshold) {
            this.beans = beans;
            this.edges = edges;
            this.cycles = cycles;
            this.conditions = conditions;
            this.timing = timing;
            this.slowBeanThreshold = slowBeanThreshold;
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
            return fromAnalysis(graph, cycles, null, null, null);
        }

        /**
         * Builds a snapshot from the live analysis including autoconfig
         * condition outcomes (v0.5.0).
         *
         * @param graph      bean name → dependency names
         * @param cycles     detected cycles, each a list of bean names
         * @param conditions autoconfig class → outcome, or {@code null} when
         *                   the condition report was unavailable
         * @return the snapshot
         */
        public static Snapshot fromAnalysis(Map<String, String[]> graph, List<List<String>> cycles,
                                            Map<String, WireDoctorConditionSnapshot.Outcome> conditions) {
            return fromAnalysis(graph, cycles, conditions, null, null);
        }

        /**
         * Builds a snapshot from the live analysis including timing data (v0.7.0).
         *
         * @param graph             bean name → dependency names
         * @param cycles            detected cycles, each a list of bean names
         * @param conditions        autoconfig class → outcome, or {@code null}
         * @param totalStartupMs    total startup time, or {@code null} when unavailable
         * @param slowBeanThreshold slow-bean threshold at snapshot time
         * @return the snapshot
         */
        public static Snapshot fromAnalysis(Map<String, String[]> graph, List<List<String>> cycles,
                                            Map<String, WireDoctorConditionSnapshot.Outcome> conditions,
                                            Long totalStartupMs, Long slowBeanThreshold) {
            Map<String, Set<String>> edges = new LinkedHashMap<>();
            graph.forEach((bean, deps) -> edges.put(bean, new LinkedHashSet<>(List.of(deps))));
            List<Set<String>> cycleSets = new ArrayList<>();
            cycles.forEach(c -> cycleSets.add(new LinkedHashSet<>(c)));
            TimingSnapshot timing = totalStartupMs != null ? new TimingSnapshot(totalStartupMs) : null;
            return new Snapshot(new LinkedHashSet<>(graph.keySet()), edges, cycleSets, conditions,
                                timing, slowBeanThreshold);
        }

        /**
         * Builds a snapshot from a parsed {@code wiredoctor-report.json} /
         * baseline file ({@code dependencies.graph} + {@code dependencies.cycles}
         * + optional top-level {@code conditions} since v0.5.0 + {@code totalStartupMs}
         * + {@code slowBeanThreshold} since v0.7.0).
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
            // Absent section → null (pre-v0.5.0 baseline): condition diff skipped.
            Map<String, WireDoctorConditionSnapshot.Outcome> conditions =
                    WireDoctorConditionSnapshot.fromJson(reportRoot.path("conditions"));

            // v0.7.0: timing data. Absent/missing → null (pre-v0.7.0 baseline): timing diff skipped.
            TimingSnapshot timing = null;
            JsonNode totalStartupNode = reportRoot.path("totalStartupMs");
            if (!totalStartupNode.isMissingNode() && totalStartupNode.isNumber()) {
                timing = new TimingSnapshot(totalStartupNode.asLong());
            }

            // v0.7.0: slow-bean threshold. Absent → null (pre-v0.7.0 baseline).
            Long slowBeanThreshold = null;
            JsonNode thresholdNode = reportRoot.path("slowBeanThreshold");
            if (!thresholdNode.isMissingNode() && thresholdNode.isNumber()) {
                slowBeanThreshold = thresholdNode.asLong();
            }

            return new Snapshot(new LinkedHashSet<>(edges.keySet()), edges, cycles, conditions,
                                timing, slowBeanThreshold);
        }

        public Set<String> beans() { return beans; }
        public Map<String, Set<String>> edges() { return edges; }
        public List<Set<String>> cycles() { return cycles; }
        /** @return autoconfig outcomes, or {@code null} when unavailable/pre-v0.5.0 */
        public Map<String, WireDoctorConditionSnapshot.Outcome> conditions() { return conditions; }
        /** @return timing data, or {@code null} when unavailable/pre-v0.7.0 */
        public TimingSnapshot timing() { return timing; }
        /** @return slow-bean threshold at baseline time, or {@code null} when pre-v0.7.0 */
        public Long slowBeanThreshold() { return slowBeanThreshold; }
    }

    /**
     * Startup timing snapshot for regression gate comparison (v0.7.0).
     *
     * @param totalStartupMs application startup time from ApplicationReadyEvent
     */
    public record TimingSnapshot(long totalStartupMs) {
    }

    /**
     * Startup time regression detected by the Cost Guardian (v0.7.0).
     * Emitted when the current run's startup time exceeds BOTH the relative
     * and absolute thresholds compared to the baseline.
     */
    public static final class StartupTimeRegression {
        private final long baselineMs;
        private final long currentMs;
        private final long deltaMs;
        private final double percentChange;

        StartupTimeRegression(long baselineMs, long currentMs) {
            this.baselineMs = baselineMs;
            this.currentMs = currentMs;
            this.deltaMs = currentMs - baselineMs;
            this.percentChange = baselineMs > 0 ? (double) deltaMs / baselineMs : 0.0;
        }

        public long baselineMs() { return baselineMs; }
        public long currentMs() { return currentMs; }
        public long deltaMs() { return deltaMs; }
        public double percentChange() { return percentChange; }
    }

    /**
     * One bean that crossed the slow-bean threshold in the current run but
     * was not slow in the baseline (v0.7.0 Cost Guardian).
     */
    public static final class NewSlowBean {
        private final String beanName;
        private final long instantiationMs;
        private final long thresholdWhenBaseline;

        NewSlowBean(String beanName, long instantiationMs, long thresholdWhenBaseline) {
            this.beanName = beanName;
            this.instantiationMs = instantiationMs;
            this.thresholdWhenBaseline = thresholdWhenBaseline;
        }

        public String beanName() { return beanName; }
        public long instantiationMs() { return instantiationMs; }
        public long thresholdWhenBaseline() { return thresholdWhenBaseline; }
    }

    /**
     * One autoconfiguration whose condition outcome flipped between baseline
     * and current run — the "cause" signal of the Upgrade Guardian (v0.5.0).
     */
    public static final class ConditionChange {
        private final String className;
        private final String oldOutcome;
        private final String newOutcome;
        private final String newReason; // null unless newOutcome carries one

        ConditionChange(String className, String oldOutcome, String newOutcome, String newReason) {
            this.className = className;
            this.oldOutcome = oldOutcome;
            this.newOutcome = newOutcome;
            this.newReason = newReason;
        }

        public String className()  { return className; }
        public String oldOutcome() { return oldOutcome; }
        public String newOutcome() { return newOutcome; }
        public String newReason()  { return newReason; }

        Map<String, Object> toReportMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("className", className);
            map.put("oldOutcome", oldOutcome);
            map.put("newOutcome", newOutcome);
            if (newReason != null) {
                map.put("newReason", newReason);
            }
            return map;
        }
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
        // v0.5.0 condition diff. conditionDiffAvailable=false when either side
        // lacks condition data (old baseline / absent report) — the three
        // lists are then empty AND meaningless, never "no changes".
        private final boolean conditionDiffAvailable;
        private final List<ConditionChange> conditionsChanged;
        private final Set<String> conditionsAdded;
        private final Set<String> conditionsRemoved;
        // v0.7.0 timing diff. null when either side lacks timing data (pre-v0.7.0 baseline).
        private final StartupTimeRegression startupTimeRegression;
        // v0.7.0 slow-bean diff. Empty when either side lacks data OR no new slow beans.
        private final List<NewSlowBean> newSlowBeans;

        DiffResult(Set<String> addedBeans, Set<String> removedBeans,
                   Set<String> addedEdges, Set<String> removedEdges,
                   List<Set<String>> newCycles, List<Set<String>> resolvedCycles) {
            this(addedBeans, removedBeans, addedEdges, removedEdges, newCycles, resolvedCycles,
                 false, List.of(), Set.of(), Set.of(), null, List.of());
        }

        DiffResult(Set<String> addedBeans, Set<String> removedBeans,
                   Set<String> addedEdges, Set<String> removedEdges,
                   List<Set<String>> newCycles, List<Set<String>> resolvedCycles,
                   boolean conditionDiffAvailable,
                   List<ConditionChange> conditionsChanged,
                   Set<String> conditionsAdded,
                   Set<String> conditionsRemoved) {
            this(addedBeans, removedBeans, addedEdges, removedEdges, newCycles, resolvedCycles,
                 conditionDiffAvailable, conditionsChanged, conditionsAdded, conditionsRemoved,
                 null, List.of());
        }

        DiffResult(Set<String> addedBeans, Set<String> removedBeans,
                   Set<String> addedEdges, Set<String> removedEdges,
                   List<Set<String>> newCycles, List<Set<String>> resolvedCycles,
                   boolean conditionDiffAvailable,
                   List<ConditionChange> conditionsChanged,
                   Set<String> conditionsAdded,
                   Set<String> conditionsRemoved,
                   StartupTimeRegression startupTimeRegression,
                   List<NewSlowBean> newSlowBeans) {
            this.addedBeans = addedBeans;
            this.removedBeans = removedBeans;
            this.addedEdges = addedEdges;
            this.removedEdges = removedEdges;
            this.newCycles = newCycles;
            this.resolvedCycles = resolvedCycles;
            this.conditionDiffAvailable = conditionDiffAvailable;
            this.conditionsChanged = conditionsChanged;
            this.conditionsAdded = conditionsAdded;
            this.conditionsRemoved = conditionsRemoved;
            this.startupTimeRegression = startupTimeRegression;
            this.newSlowBeans = newSlowBeans;
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
        /** @return {@code true} when BOTH snapshots carried condition data and the diff ran */
        public boolean conditionDiffAvailable() { return conditionDiffAvailable; }
        /** Autoconfigs whose outcome flipped (matched → notMatched etc.) — the headline list. */
        public List<ConditionChange> conditionsChanged() { return conditionsChanged; }
        /** Autoconfig classes evaluated now but not in the baseline (Boot version bump). */
        public Set<String> conditionsAdded() { return conditionsAdded; }
        /** Autoconfig classes in the baseline but no longer evaluated. */
        public Set<String> conditionsRemoved() { return conditionsRemoved; }

        /** @return {@code true} when nothing changed at all */
        public boolean isEmpty() {
            return addedBeans.isEmpty() && removedBeans.isEmpty()
                    && addedEdges.isEmpty() && removedEdges.isEmpty()
                    && newCycles.isEmpty() && resolvedCycles.isEmpty()
                    && conditionsChanged.isEmpty()
                    && conditionsAdded.isEmpty() && conditionsRemoved.isEmpty()
                    && startupTimeRegression == null
                    && newSlowBeans.isEmpty();
        }

        /** @return {@code true} when at least one new cycle was introduced */
        public boolean hasNewCycles() {
            return !newCycles.isEmpty();
        }

        /** @return {@code true} when at least one condition outcome flipped (v0.5.0 gate signal) */
        public boolean hasConditionChanges() {
            return !conditionsChanged.isEmpty();
        }

        /** @return startup time regression, or {@code null} when timing diff unavailable (v0.7.0 gate signal) */
        public StartupTimeRegression startupTimeRegression() {
            return startupTimeRegression;
        }

        /** @return new slow beans list (v0.7.0 gate signal); empty when unavailable or no new slow beans */
        public List<NewSlowBean> newSlowBeans() {
            return newSlowBeans;
        }

        /** @return {@code true} when startup time regressed beyond thresholds (v0.7.0 gate signal) */
        public boolean hasStartupTimeRegression() {
            return startupTimeRegression != null;
        }

        /** @return {@code true} when at least one bean became slow (v0.7.0 gate signal) */
        public boolean hasNewSlowBeans() {
            return !newSlowBeans.isEmpty();
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
            // v0.5.0: condition diff section. "available" lets a consumer tell
            // "no condition changes" from "condition diff could not run".
            Map<String, Object> conditionDiff = new LinkedHashMap<>();
            conditionDiff.put("available", conditionDiffAvailable);
            conditionDiff.put("changedCount", conditionsChanged.size());
            List<Map<String, Object>> changed = new ArrayList<>();
            conditionsChanged.forEach(c -> changed.add(c.toReportMap()));
            conditionDiff.put("changed", changed);
            conditionDiff.put("addedCount", conditionsAdded.size());
            conditionDiff.put("added", new ArrayList<>(conditionsAdded));
            conditionDiff.put("removedCount", conditionsRemoved.size());
            conditionDiff.put("removed", new ArrayList<>(conditionsRemoved));
            map.put("conditionDiff", conditionDiff);

            // v0.7.0: startup time regression section
            if (startupTimeRegression != null) {
                Map<String, Object> timingDiff = new LinkedHashMap<>();
                timingDiff.put("baselineMs", startupTimeRegression.baselineMs());
                timingDiff.put("currentMs", startupTimeRegression.currentMs());
                timingDiff.put("deltaMs", startupTimeRegression.deltaMs());
                timingDiff.put("percentChange", startupTimeRegression.percentChange());
                map.put("startupTimeDiff", timingDiff);
            }

            // v0.7.0: new slow beans section
            if (!newSlowBeans.isEmpty()) {
                List<Map<String, Object>> slowBeansList = new ArrayList<>();
                for (NewSlowBean bean : newSlowBeans) {
                    Map<String, Object> beanMap = new LinkedHashMap<>();
                    beanMap.put("beanName", bean.beanName());
                    beanMap.put("instantiationMs", bean.instantiationMs());
                    beanMap.put("thresholdWhenBaseline", bean.thresholdWhenBaseline());
                    slowBeansList.add(beanMap);
                }
                map.put("newSlowBeans", slowBeansList);
            }

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

        // ── v0.5.0: condition diff — only when BOTH sides carry condition data.
        // An old baseline (null conditions) must skip, not report every current
        // autoconfig as "added".
        boolean conditionDiffAvailable =
                baseline.conditions() != null && current.conditions() != null;
        List<ConditionChange> conditionsChanged = new ArrayList<>();
        Set<String> conditionsAdded = new TreeSet<>();
        Set<String> conditionsRemoved = new TreeSet<>();
        if (conditionDiffAvailable) {
            Map<String, WireDoctorConditionSnapshot.Outcome> base = baseline.conditions();
            Map<String, WireDoctorConditionSnapshot.Outcome> curr = current.conditions();
            // TreeMap iteration → changed list is sorted by class name.
            for (Map.Entry<String, WireDoctorConditionSnapshot.Outcome> e
                    : new java.util.TreeMap<>(curr).entrySet()) {
                WireDoctorConditionSnapshot.Outcome old = base.get(e.getKey());
                if (old == null) {
                    conditionsAdded.add(e.getKey());
                } else if (!old.equals(e.getValue())) {
                    conditionsChanged.add(new ConditionChange(
                            e.getKey(), old.outcome(), e.getValue().outcome(),
                            e.getValue().reason()));
                }
            }
            for (String baseClass : base.keySet()) {
                if (!curr.containsKey(baseClass)) conditionsRemoved.add(baseClass);
            }
        }

        // ── v0.7.0: timing diff — only when BOTH sides carry timing data.
        // Pre-v0.7.0 baseline → timing null → diff skipped, not reported as regression.
        StartupTimeRegression startupTimeRegression = null;
        if (baseline.timing() != null && current.timing() != null) {
            long baselineMs = baseline.timing().totalStartupMs();
            long currentMs = current.timing().totalStartupMs();
            if (currentMs > baselineMs) {
                // Compute regression, but caller decides if it trips the gate
                // based on dual-threshold check (done in WireDoctorAnalyzer).
                startupTimeRegression = new StartupTimeRegression(baselineMs, currentMs);
            }
        }

        // ── v0.7.0: slow-bean diff — beans that crossed the threshold NOW but
        // were NOT slow in the baseline. Requires slowBeans data from BOTH sides.
        // Note: we're comparing by bean NAME presence in the baseline slowBeans
        // array, NOT by threshold value (threshold may have changed between runs;
        // we care about "was it slow then or not").
        List<NewSlowBean> newSlowBeans = new ArrayList<>();
        // This requires the CURRENT report to have slowBeans data. The baseline's
        // slowBeanThreshold is used for reporting context (what threshold applied then).
        // Caller must pass current slowBeans list + current threshold; we can't extract
        // it from the Snapshot (Snapshot is for baseline/report JSON, not live analysis).
        // So this diff is INCOMPLETE here — the actual slow-bean comparison happens
        // in WireDoctorAnalyzer where we have both baseline snapshot AND current live
        // slowBeans list. We leave newSlowBeans empty here as a placeholder; the real
        // logic goes in the analyzer's runRegressionGuard method.
        // TODO: refactor if needed, but for now this mirrors the condition diff pattern
        // where WireDoctorAnalyzer drives the comparison.

        return new DiffResult(
                Collections.unmodifiableSet(addedBeans),
                Collections.unmodifiableSet(removedBeans),
                Collections.unmodifiableSet(addedEdges),
                Collections.unmodifiableSet(removedEdges),
                Collections.unmodifiableList(newCycles),
                Collections.unmodifiableList(resolvedCycles),
                conditionDiffAvailable,
                Collections.unmodifiableList(conditionsChanged),
                Collections.unmodifiableSet(conditionsAdded),
                Collections.unmodifiableSet(conditionsRemoved),
                startupTimeRegression,
                Collections.unmodifiableList(newSlowBeans));
    }

    private static Set<String> flattenEdges(Map<String, Set<String>> edges) {
        Set<String> flat = new HashSet<>();
        edges.forEach((from, targets) -> targets.forEach(to -> flat.add(from + " -> " + to)));
        return flat;
    }

    /**
     * Computes the new slow beans by comparing current slowBeans list against
     * the baseline's slowBeans list (v0.7.0 Cost Guardian).
     * <p>
     * A bean is "new slow" when it appears in the current slowBeans list but
     * NOT in the baseline's slowBeans list — regardless of threshold changes
     * between runs (we care about "was it slow then", not "what was the threshold").
     * <p>
     * v0.8.0 jitter guard: for gating, the bean must additionally exceed
     * {@code currentThreshold + marginMs}. A bean sitting in the margin band
     * (e.g. 101ms vs a 100ms threshold with a 20ms margin) stays in the
     * report's slowBeans list but does NOT become a gate-tripping regression —
     * real-world validation showed that band is JVM jitter, not signal. This
     * mirrors the dual-threshold noise tolerance of the startup-time gate.
     * Pass {@code 0} for exact pre-v0.8.0 behavior.
     * <p>
     * Called from {@code WireDoctorAnalyzer} after the main diff, since it
     * needs the live current slowBeans data not present in the snapshot.
     *
     * @param baselineSlowBeanNames set of bean names that were slow in baseline
     * @param baselineThreshold     baseline's slow-bean threshold (for reporting)
     * @param currentSlowBeans      current run's slow beans list (from report)
     * @param currentThreshold      current run's slow-bean threshold
     * @param marginMs              jitter margin added to the threshold for gating
     * @return list of beans that became slow; empty when baseline lacks data
     */
    public static List<NewSlowBean> computeNewSlowBeans(
            Set<String> baselineSlowBeanNames,
            long baselineThreshold,
            java.util.List<Map<String, Object>> currentSlowBeans,
            long currentThreshold,
            long marginMs) {

        List<NewSlowBean> newSlowBeans = new ArrayList<>();
        for (Map<String, Object> bean : currentSlowBeans) {
            // Report shape from WireDoctorAnalyzer: {"beanName": ..., "durationMs": ...}
            String beanName = (String) bean.get("beanName");
            Number durationMsNum = (Number) bean.get("durationMs");
            if (beanName != null && durationMsNum != null) {
                long instantiationMs = durationMsNum.longValue();
                // New slow: in current slowBeans but NOT in baseline slowBeans,
                // and past the jitter margin band.
                if (!baselineSlowBeanNames.contains(beanName)
                        && instantiationMs > currentThreshold + marginMs) {
                    newSlowBeans.add(new NewSlowBean(beanName, instantiationMs, baselineThreshold));
                }
            }
        }
        return newSlowBeans;
    }
}
