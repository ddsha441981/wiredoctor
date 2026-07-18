/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.actuator;

import com.wiredoctor.WireDoctorAnalyzer;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

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
 * Lives in the separate {@code wiredoctor-actuator} module so the core artifact
 * stays actuator-free (the v0.1.1 classpath-neutrality guarantee).
 *
 * @author Deendayal Kumawat
 * @since 0.4.0
 */
@Endpoint(id = "wiredoctor")
public class WireDoctorEndpoint {

    private final WireDoctorAnalyzer analyzer;

    /**
     * @param analyzer the core analyzer whose last report this endpoint serves
     */
    public WireDoctorEndpoint(WireDoctorAnalyzer analyzer) {
        this.analyzer = analyzer;
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
}
