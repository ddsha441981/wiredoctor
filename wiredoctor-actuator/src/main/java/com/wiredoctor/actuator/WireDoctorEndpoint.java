/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.actuator;

import com.wiredoctor.WireDoctorAnalyzer;
import com.wiredoctor.WireDoctorGhostTracker;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator endpoint exposing the WireDoctor diagnostic report over
 * {@code /actuator/wiredoctor}.
 * <p>
 * Read-only: it serves whatever the core {@link WireDoctorAnalyzer} produced at
 * application-ready time (the same report written to {@code wiredoctor-report.json}),
 * held in memory. No analysis is triggered by an HTTP call — the endpoint never
 * touches the {@code BeanFactory}, consistent with WireDoctor's zero-intrusion
 * guarantees.
 * <p>
 * Since v0.6.0, {@code /actuator/wiredoctor/ghosts} additionally serves the
 * <em>live</em> ghost-tracking state (touched / untouched / untrackable) when
 * the user has opted into {@code wiredoctor.ghost-tracking.enabled=true} —
 * the on-demand view for long-running staging environments, where waiting for
 * the shutdown report is impractical. Reading the state is a pure snapshot of
 * already-collected flags; it triggers nothing.
 * <p>
 * Lives in the separate {@code wiredoctor-actuator} module so the core artifact
 * stays actuator-free (the v0.1.1 classpath-neutrality guarantee).
 *
 * @author Deendayal Kumawat
 * @since 0.4.0
 */
@Endpoint(id = "wiredoctor")
public class WireDoctorEndpoint {

    private final WireDoctorAnalyzer analyzer;

    /** Live ghost-tracking state, or {@code null} when tracking is not enabled. */
    private final WireDoctorGhostTracker ghostTracker;

    /**
     * @param analyzer     the core analyzer whose last report this endpoint serves
     * @param ghostTracker the live ghost-tracking state, or {@code null} when
     *                     {@code wiredoctor.ghost-tracking.enabled} is off
     */
    public WireDoctorEndpoint(WireDoctorAnalyzer analyzer, WireDoctorGhostTracker ghostTracker) {
        this.analyzer = analyzer;
        this.ghostTracker = ghostTracker;
    }

    /**
     * Serves the most recent diagnostic report.
     *
     * @return the report map produced at startup, or a small
     *         {@code {status: "PENDING"}} placeholder if analysis has not
     *         completed yet (e.g. queried before {@code ApplicationReadyEvent},
     *         or WireDoctor is disabled)
     */
    @ReadOperation
    public Map<String, Object> report() {
        Map<String, Object> report = analyzer.getLastReport();
        if (report == null) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("status", "PENDING");
            pending.put("message", "WireDoctor analysis has not completed yet.");
            return pending;
        }
        return report;
    }

    /**
     * Serves a section of the report by name — {@code ghosts} is special-cased
     * to the LIVE ghost-tracking state (v0.6.0); any other selector returns the
     * matching top-level section of the retained startup report.
     *
     * @param section {@code ghosts} for live tracking state, or a report
     *                section name ({@code smells}, {@code dependencies}, ...)
     * @return the requested data, or a descriptive placeholder when unavailable
     */
    @ReadOperation
    public Map<String, Object> section(@Selector String section) {
        if ("ghosts".equals(section)) {
            return ghosts();
        }
        Map<String, Object> report = analyzer.getLastReport();
        Object value = report != null ? report.get(section) : null;
        if (value instanceof Map<?, ?> mapValue) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) mapValue;
            return typed;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (value != null) {
            result.put(section, value);
        } else {
            result.put("status", "NOT_FOUND");
            result.put("message", "No report section named '" + section + "'.");
        }
        return result;
    }

    private Map<String, Object> ghosts() {
        if (ghostTracker == null) {
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("status", "DISABLED");
            disabled.put("message",
                    "Ghost tracking is not enabled. Set wiredoctor.ghost-tracking.enabled=true "
                    + "(dev/staging only — wraps eligible beans in a thin counting proxy).");
            return disabled;
        }
        // Live snapshot: reflects invocations up to this moment.
        return ghostTracker.toReportMap();
    }
}
