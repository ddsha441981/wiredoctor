/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.actuator;

import com.wiredoctor.WireDoctorAnalyzer;
import com.wiredoctor.WireDoctorGhostTracker;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorEndpoint}: it serves the analyzer's retained
 * report and degrades gracefully before analysis has run; since v0.6.0 the
 * {@code ghosts} selector serves live tracking state (or an honest DISABLED
 * placeholder when the user did not opt in).
 */
class WireDoctorEndpointTest {

    @Test
    void servesRetainedReportWhenPresent() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("smells", Map.of("frameworkFiltered", true));
        Mockito.when(analyzer.getLastReport()).thenReturn(report);

        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer, null);

        assertThat(endpoint.report()).isSameAs(report);
    }

    @Test
    void returnsPendingPlaceholderBeforeAnalysisCompletes() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        Mockito.when(analyzer.getLastReport()).thenReturn(null);

        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer, null);

        Map<String, Object> result = endpoint.report();
        assertThat(result).containsEntry("status", "PENDING");
        assertThat(result).containsKey("message");
    }

    // ── /wiredoctor/{section} selector ───────────────────────────────────────

    @Test
    void selectorServesReportSection() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("smells", Map.of("frameworkFiltered", true));
        Mockito.when(analyzer.getLastReport()).thenReturn(report);

        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer, null);

        assertThat(endpoint.section("smells")).containsEntry("frameworkFiltered", true);
    }

    @Test
    void selectorUnknownSectionReturnsNotFound() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        Mockito.when(analyzer.getLastReport()).thenReturn(new LinkedHashMap<>());

        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer, null);

        assertThat(endpoint.section("nope")).containsEntry("status", "NOT_FOUND");
    }

    // ── /wiredoctor/ghosts ───────────────────────────────────────────────────

    @Test
    void ghostsWithoutOptInReturnsDisabledWithHowTo() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer, null);

        Map<String, Object> result = endpoint.section("ghosts");
        assertThat(result).containsEntry("status", "DISABLED");
        assertThat((String) result.get("message"))
                .contains("wiredoctor.ghost-tracking.enabled=true");
    }

    @Test
    void ghostsWithTrackerServesLiveState() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();

        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer, tracker);

        Map<String, Object> result = endpoint.section("ghosts");
        assertThat(result).containsKeys("trackedCount", "touched", "untouched", "untrackable");
        assertThat((String) result.get("disclaimer")).contains("NOT that the bean is unused");
    }
}
