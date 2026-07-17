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
        if (failOn == null || failOn.isBlank()) return false;
        for (String gate : failOn.split(",")) {
            if ("new-cycle".equals(gate.trim())) return true;
        }
        return false;
    }
}
