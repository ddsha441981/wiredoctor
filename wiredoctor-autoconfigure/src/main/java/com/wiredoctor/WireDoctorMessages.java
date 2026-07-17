/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

/**
 * Centralized constant definitions for all console and log messages 
 * emitted by the WireDoctor diagnostic suite.
 * <p>
 * Ensures consistency in formatting, localization readiness, and simplifies 
 * log structure modifications without impacting core analysis logic.
 *
 * @author Deendayal Kumawat
 * @since 0.1.0
 */
public final class WireDoctorMessages {

    public static final String BANNER_TOP = "\n==================================================";
    public static final String BANNER_TEXT = " WIREDOCTOR ANALYSIS STARTING";
    public static final String BANNER_BOTTOM = "==================================================";
    
    public static final String INTERCEPTING_STARTUP = "[WireDoctor] Intercepting startup to register BufferingApplicationStartup...";
    public static final String STARTUP_NOT_BUFFERING_WARNING = "[WireDoctor] WARNING: ApplicationStartup is not BufferingApplicationStartup! It is: {}";
    
    public static final String SAVED_JSON_REPORT = "[WireDoctor] Saved detailed report to: {}";
    public static final String SAVED_HTML_REPORT = "[WireDoctor] Saved interactive HTML visualizer to: {}";
    
    public static final String FAILED_WRITE_JSON = "[WireDoctor] Failed to write reports: {}";
    public static final String FAILED_WRITE_HTML = "[WireDoctor] Failed to write HTML report: {}";
    
    public static final String SLOWEST_STEPS_HEADER = "[WireDoctor] Slowest Startup Steps:";
    public static final String SLOWEST_STEP_ITEM = "  - {} ({}ms)";
    
    public static final String CYCLES_HEADER = "\n[WireDoctor] Bean Dependency Cycles: {}";
    public static final String CYCLE_ITEM = "  - Cycle detected: {} -> {}";
    public static final String CYCLE_NOTE = "    (Note: Spring resolved this via proxy/setter, but structurally it is a cycle)";
    
    public static final String PROXY_HEADER = "\n[WireDoctor] Proxy Overhead:";
    public static final String PROXY_CGLIB_ITEM = "  - CGLIB Proxies: {}";
    public static final String PROXY_JDK_ITEM = "  - JDK Proxies: {}";
    
    public static final String BANNER_END = "==================================================\n";

    public static final String BEAN_CATEGORIES_HEADER = "\n[WireDoctor] Bean Category Summary:";
    public static final String BEAN_USER_DEFINED     = "  - User-defined Beans : {}";
    public static final String BEAN_FRAMEWORK        = "  - Framework Beans    : {}";
    public static final String BEAN_ROLE_APP         = "  - Role Application   : {}";
    public static final String BEAN_ROLE_INFRA       = "  - Role Infrastructure: {}";

    public static final String SLOW_BEANS_HEADER     = "\n[WireDoctor] Slow Bean Instantiation (threshold: {}ms):";
    public static final String SLOW_BEANS_NONE       = "  - None detected above threshold";
    public static final String SLOW_BEAN_ITEM        = "  - {} ({}ms)";

    public static final String OUTPUT_PATH_INFO      = "[WireDoctor] Output directory: {}";

    public static final String BAD_THRESHOLD        = "[WireDoctor] Invalid value '{}' for wiredoctor.slow-bean-threshold-ms; using default {}ms";
    public static final String ANALYSIS_FAILED      = "[WireDoctor] Analysis failed; the host application is NOT affected. Cause:";
    public static final String PROXY_SKIPPED_ITEM   = "  - Not instantiated (skipped): {}";

    public static final String BASELINE_WRITTEN       = "[WireDoctor] Baseline written to: {}";
    public static final String BASELINE_WRITE_FAILED  = "[WireDoctor] Could not write baseline to {}: {}";
    public static final String BASELINE_MISSING       = "[WireDoctor] No baseline found at {} — skipping diff. Run once with wiredoctor.baseline-write=true to create it.";
    public static final String BASELINE_UNREADABLE    = "[WireDoctor] Baseline at {} could not be parsed ({}) — skipping diff.";
    public static final String DIFF_HEADER            = "\n[WireDoctor] Baseline Diff (vs {}):";
    public static final String DIFF_NO_CHANGES        = "  - No architectural changes vs baseline";
    public static final String DIFF_SUMMARY           = "  - Beans: +{} -{} | Edges: +{} -{} | New cycles: {} | Resolved cycles: {}";
    public static final String DIFF_NEW_CYCLE_ITEM    = "  - NEW CYCLE: {}";
    public static final String DIFF_SAVED             = "[WireDoctor] Saved baseline diff to: {}";
    public static final String GATE_TRIPPED           = "[WireDoctor] REGRESSION GATE TRIPPED (wiredoctor.fail-on={}): {} new cycle(s) introduced vs baseline. Failing the application as configured.";

    public static final String CRITICAL_PATH_HEADER_PCT = "\n[WireDoctor] Startup Critical Path ({}% of readiness, {}ms):";
    public static final String CRITICAL_PATH_HEADER     = "\n[WireDoctor] Startup Critical Path ({}ms):";
    public static final String CRITICAL_PATH_CHAIN      = "  - {}";
    public static final String CRITICAL_PATH_NOTE       = "    (Instantiation-weighted approximation; parallel init is not modeled)";

    public static final String VIS_BUNDLE_UNAVAILABLE   = "[WireDoctor] Bundled vis-network library unavailable ({}); HTML report falls back to CDN (needs internet for the graph view).";

    public static final String LAZY_SUGGESTIONS_HEADER  = "\n[WireDoctor] @Lazy Suggestions to Break Cycles:";
    public static final String LAZY_SUGGESTION_ITEM     = "  {}. Make '{}' @Lazy (breaks {} cycle(s), impacts {} bean(s))";

    public static final String SMELLS_HEADER            = "\n[WireDoctor] Architecture Smells (live graph):";
    public static final String SMELL_FAN_IN_ITEM        = "  - Coupling hotspot : '{}' has {} dependents (fan-in)";
    public static final String SMELL_FAN_OUT_ITEM       = "  - Wide dependency  : '{}' depends on {} beans (fan-out)";
    public static final String SMELL_UNSTABLE_ITEM      = "  - Unstable         : '{}' I={} (fan-in {}, fan-out {})";

    private WireDoctorMessages() {
        // Prevent instantiation
    }
}
