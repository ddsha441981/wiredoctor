/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.actuator;

import com.wiredoctor.WireDoctorAnalyzer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorEndpoint}: it serves the analyzer's retained
 * report and degrades gracefully before analysis has run.
 */
class WireDoctorEndpointTest {

    @Test
    void servesRetainedReportWhenPresent() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("smells", Map.of("frameworkFiltered", true));
        Mockito.when(analyzer.getLastReport()).thenReturn(report);

        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer);

        assertThat(endpoint.report()).isSameAs(report);
    }

    @Test
    void returnsPendingPlaceholderBeforeAnalysisCompletes() {
        WireDoctorAnalyzer analyzer = Mockito.mock(WireDoctorAnalyzer.class);
        Mockito.when(analyzer.getLastReport()).thenReturn(null);

        WireDoctorEndpoint endpoint = new WireDoctorEndpoint(analyzer);

        Map<String, Object> result = endpoint.report();
        assertThat(result).containsEntry("status", "PENDING");
        assertThat(result).containsKey("message");
    }
}
