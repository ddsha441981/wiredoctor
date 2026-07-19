/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration properties for the WireDoctor diagnostic suite,
 * bound from the {@code wiredoctor.*} namespace.
 * <p>
 * All defaults live here, in one place. Values that cannot bind cleanly are
 * handled leniently ({@code slowBeanThresholdMs} binds as a raw string and is
 * parsed with a safe fallback) so a bad configuration value can never fail
 * host application startup — consistent with the v0.1.1 trust guarantees.
 *
 * @author Deendayal Kumawat
 * @since 0.1.2
 */
@ConfigurationProperties(prefix = "wiredoctor")
public class WireDoctorProperties {

    /** Default slow-bean threshold applied when no or an invalid value is configured. */
    public static final long DEFAULT_SLOW_BEAN_THRESHOLD_MS = 100L;

    /** Default cap on graph nodes serialized into the JSON/HTML report. */
    public static final int DEFAULT_MAX_GRAPH_NODES = 2000;

    /**
     * Whether WireDoctor runs at all. Set to {@code false} to fully disable
     * analysis, report writing, and console output (e.g. in production).
     */
    private boolean enabled = true;

    /**
     * Directory where the JSON and HTML reports are written.
     * Defaults to the process working directory.
     */
    private String outputPath = System.getProperty("user.dir");

    /**
     * Comma-separated package prefixes to analyze for orphan beans.
     * When empty, WireDoctor filters out well-known framework packages instead.
     */
    private String scanPackages;

    /**
     * Threshold in milliseconds above which a bean instantiation is flagged as slow.
     * <p>
     * Deliberately typed as {@link String}: a malformed value (e.g. {@code "50ms"})
     * must degrade to the default with a warning, never fail context startup.
     * Use {@link #resolveSlowBeanThresholdMs()} to obtain the parsed value.
     */
    private String slowBeanThresholdMs = String.valueOf(DEFAULT_SLOW_BEAN_THRESHOLD_MS);

    /**
     * Path to a committed baseline report (typically
     * {@code wiredoctor-baseline.json} in the repo root). When set, the current
     * bean graph is diffed against it and {@code wiredoctor-diff.json} is
     * written. A missing/unreadable file logs an info message and skips the
     * diff — it never fails the application.
     * <p>
     * The path may contain a {@code {profiles}} token (v0.4.0), replaced at
     * runtime with a stable key derived from the active profiles (sorted,
     * dash-joined, or {@code default} when none are active). For example
     * {@code wiredoctor-baseline-{profiles}.json} diffs a {@code prod} run
     * against {@code wiredoctor-baseline-prod.json} — so profile-specific bean
     * graphs compare like-with-like instead of producing spurious churn. A path
     * without the token behaves exactly as before (a single shared baseline).
     */
    private String baseline;

    /**
     * When {@code true}, the current run's report is (re)written to the
     * {@link #baseline} path instead of being diffed against it. Use this to
     * intentionally accept the current architecture as the new baseline.
     */
    private boolean baselineWrite = false;

    /**
     * Comma-separated regression gates that fail the application after the
     * report is written. Supported in v0.2.0: {@code new-cycle}. Empty
     * (default) means report-only — the diff is written but never gates.
     */
    private String failOn;

    /**
     * Maximum number of graph nodes serialized into the JSON report (and thus
     * the HTML visualizer). Above the cap, the graph is truncated to the top-N
     * beans by fan-in (the most-depended-on nodes carry the most signal) and
     * {@code dependencies.graphTruncated} is set. Analysis itself (cycles,
     * smells, critical path, diff) always runs on the FULL graph — only the
     * serialized {@code graph} section is capped.
     * <p>
     * Deliberately typed as {@link String}: a malformed value must degrade to
     * the default with a warning, never fail context startup. {@code 0} means
     * unlimited (pre-v0.3.0 behavior). Use {@link #resolveMaxGraphNodes()}.
     */
    private String maxGraphNodes = String.valueOf(DEFAULT_MAX_GRAPH_NODES);

    /**
     * Whether the architecture-smell rankings ({@code highFanIn},
     * {@code highFanOut}, {@code unstable}) include framework-owned beans.
     * <p>
     * Defaults to {@code false}: framework beans (Spring, Tomcat, Jackson, ...)
     * genuinely are the coupling hotspots of any Boot app, but users cannot
     * refactor them, so ranking them first buries the actionable user-bean
     * signal. The full {@code fanIn} map (used for HTML node sizing) is always
     * complete regardless of this toggle. Set to {@code true} to rank every
     * bean, framework included.
     */
    private boolean includeFrameworkSmells = false;

    /**
     * Opt-in ghost tracking (v0.6.0 Phase 2). OFF by default — the doctor's
     * rule: no medicine without consent. When enabled, eligible user singletons
     * are wrapped in a thin first-touch counting proxy; use in dev/staging,
     * not prod.
     */
    private final GhostTracking ghostTracking = new GhostTracking();

    /**
     * Relative threshold for startup-time regression gate (v0.7.0 Cost Guardian).
     * The gate trips when the startup time increase exceeds BOTH this percentage
     * AND {@link #startupTimeAbsoluteThreshold}. Default {@code 0.20} (20%).
     * <p>
     * Example: baseline 3000ms, current 3700ms → +700ms = 23% → trips (>20%).
     * But: baseline 100ms, current 130ms → +30ms = 30% → does NOT trip (absolute
     * <500ms) — prevents false alarms on fast apps where small absolute noise
     * looks like large percentage change.
     */
    private double startupTimeRelativeThreshold = 0.20;

    /**
     * Absolute threshold for startup-time regression gate (v0.7.0 Cost Guardian).
     * The gate trips when the startup time increase exceeds BOTH this millisecond
     * value AND {@link #startupTimeRelativeThreshold}. Default {@code 500} (500ms).
     * <p>
     * Dual-threshold design: prevents flapping on noise. A 50ms regression in a
     * 200ms app is 25% (large) but only 50ms (noise) — does not trip.
     */
    private long startupTimeAbsoluteThreshold = 500;

    /** Nested {@code wiredoctor.ghost-tracking.*} properties. */
    public static class GhostTracking {

        /**
         * Master switch for first-touch tracking. {@code false} (default)
         * keeps the artifact 100% passive: the tracking
         * {@code BeanPostProcessor} is never registered at all.
         */
        private boolean enabled = false;

        /**
         * Comma-separated bean names to exclude from proxy wrapping. Excluded
         * beans are counted and reported as untrackable ({@code excluded}),
         * never silently hidden.
         */
        private String exclude;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getExclude() {
            return exclude;
        }

        public void setExclude(String exclude) {
            this.exclude = exclude;
        }

        /**
         * @return the parsed exclude list; empty when unset
         */
        public java.util.Set<String> resolveExcludedBeans() {
            if (exclude == null || exclude.isBlank()) {
                return java.util.Set.of();
            }
            java.util.Set<String> names = new java.util.HashSet<>();
            for (String name : exclude.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
            return names;
        }
    }

    /**
     * Parses {@link #slowBeanThresholdMs} leniently.
     *
     * @return the configured threshold, or {@value #DEFAULT_SLOW_BEAN_THRESHOLD_MS}
     *         if the raw value is missing or not a valid number
     */
    public long resolveSlowBeanThresholdMs() {
        if (slowBeanThresholdMs == null) {
            return DEFAULT_SLOW_BEAN_THRESHOLD_MS;
        }
        try {
            return Long.parseLong(slowBeanThresholdMs.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_SLOW_BEAN_THRESHOLD_MS;
        }
    }

    /**
     * @return {@code true} when the configured threshold string is a valid number
     */
    public boolean isSlowBeanThresholdValid() {
        if (slowBeanThresholdMs == null) {
            return true; // absent = default, not invalid
        }
        try {
            Long.parseLong(slowBeanThresholdMs.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public String getScanPackages() {
        return scanPackages;
    }

    public void setScanPackages(String scanPackages) {
        this.scanPackages = scanPackages;
    }

    public String getSlowBeanThresholdMs() {
        return slowBeanThresholdMs;
    }

    public void setSlowBeanThresholdMs(String slowBeanThresholdMs) {
        this.slowBeanThresholdMs = slowBeanThresholdMs;
    }

    public String getBaseline() {
        return baseline;
    }

    public void setBaseline(String baseline) {
        this.baseline = baseline;
    }

    public boolean isBaselineWrite() {
        return baselineWrite;
    }

    public void setBaselineWrite(boolean baselineWrite) {
        this.baselineWrite = baselineWrite;
    }

    public String getFailOn() {
        return failOn;
    }

    public void setFailOn(String failOn) {
        this.failOn = failOn;
    }

    /**
     * @return {@code true} when the {@code new-cycle} gate is enabled via {@link #failOn}
     */
    public boolean isFailOnNewCycle() {
        return hasGate("new-cycle");
    }

    /**
     * @return {@code true} when the {@code condition-changed} gate is enabled
     *         via {@link #failOn} (v0.5.0 Upgrade Guardian)
     */
    public boolean isFailOnConditionChanged() {
        return hasGate("condition-changed");
    }

    /**
     * @return {@code true} when the {@code startup-time} gate is enabled
     *         via {@link #failOn} (v0.7.0 Cost Guardian)
     */
    public boolean isFailOnStartupTime() {
        return hasGate("startup-time");
    }

    /**
     * @return {@code true} when the {@code slow-bean} gate is enabled
     *         via {@link #failOn} (v0.7.0 Cost Guardian)
     */
    public boolean isFailOnSlowBean() {
        return hasGate("slow-bean");
    }

    private boolean hasGate(String gate) {
        if (failOn == null || failOn.isBlank()) return false;
        for (String candidate : failOn.split(",")) {
            if (gate.equals(candidate.trim())) return true;
        }
        return false;
    }

    public String getMaxGraphNodes() {
        return maxGraphNodes;
    }

    public void setMaxGraphNodes(String maxGraphNodes) {
        this.maxGraphNodes = maxGraphNodes;
    }

    public boolean isIncludeFrameworkSmells() {
        return includeFrameworkSmells;
    }

    public void setIncludeFrameworkSmells(boolean includeFrameworkSmells) {
        this.includeFrameworkSmells = includeFrameworkSmells;
    }

    public GhostTracking getGhostTracking() {
        return ghostTracking;
    }

    public double getStartupTimeRelativeThreshold() {
        return startupTimeRelativeThreshold;
    }

    public void setStartupTimeRelativeThreshold(double startupTimeRelativeThreshold) {
        this.startupTimeRelativeThreshold = startupTimeRelativeThreshold;
    }

    public long getStartupTimeAbsoluteThreshold() {
        return startupTimeAbsoluteThreshold;
    }

    public void setStartupTimeAbsoluteThreshold(long startupTimeAbsoluteThreshold) {
        this.startupTimeAbsoluteThreshold = startupTimeAbsoluteThreshold;
    }

    /**
     * Parses {@link #maxGraphNodes} leniently.
     *
     * @return the configured cap, or {@value #DEFAULT_MAX_GRAPH_NODES} if the
     *         raw value is missing, negative, or not a valid number; {@code 0}
     *         means unlimited
     */
    public int resolveMaxGraphNodes() {
        if (maxGraphNodes == null) {
            return DEFAULT_MAX_GRAPH_NODES;
        }
        try {
            int parsed = Integer.parseInt(maxGraphNodes.trim());
            return parsed < 0 ? DEFAULT_MAX_GRAPH_NODES : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_GRAPH_NODES;
        }
    }
}
