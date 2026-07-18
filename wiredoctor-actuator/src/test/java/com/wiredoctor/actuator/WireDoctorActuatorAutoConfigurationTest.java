/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.actuator;

import com.wiredoctor.WireDoctorAnalyzer;
import com.wiredoctor.WireDoctorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the endpoint auto-configures when actuator is present and WireDoctor
 * is enabled, and backs off otherwise.
 */
class WireDoctorActuatorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    EndpointAutoConfiguration.class,
                    WebEndpointAutoConfiguration.class,
                    WireDoctorAutoConfiguration.class,
                    WireDoctorActuatorAutoConfiguration.class));

    @Test
    void registersEndpointWhenExposedAndAnalyzerPresent() {
        runner.withPropertyValues("management.endpoints.web.exposure.include=wiredoctor")
                .run(context -> {
                    assertThat(context).hasSingleBean(WireDoctorEndpoint.class);
                    assertThat(context).hasSingleBean(WireDoctorAnalyzer.class);
                });
    }

    @Test
    void backsOffWhenEndpointNotExposed() {
        // Default exposure does not include "wiredoctor" → endpoint not available.
        runner.run(context ->
                assertThat(context).doesNotHaveBean(WireDoctorEndpoint.class));
    }

    @Test
    void backsOffWhenWireDoctorDisabled() {
        runner.withPropertyValues(
                        "management.endpoints.web.exposure.include=wiredoctor",
                        "wiredoctor.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WireDoctorAnalyzer.class);
                    assertThat(context).doesNotHaveBean(WireDoctorEndpoint.class);
                });
    }
}
