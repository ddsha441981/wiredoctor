/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor.actuator;

import com.wiredoctor.WireDoctorAnalyzer;
import com.wiredoctor.WireDoctorGhostTracker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * {@link AutoConfiguration Auto-configuration} for the optional WireDoctor
 * Actuator endpoint.
 * <p>
 * Registers {@link WireDoctorEndpoint} only when:
 * <ul>
 *   <li>Spring Boot Actuator is on the classpath ({@link Endpoint} present),</li>
 *   <li>a core {@link WireDoctorAnalyzer} bean exists (WireDoctor is enabled),</li>
 *   <li>and the {@code wiredoctor} endpoint is exposed per the standard actuator
 *       exposure rules ({@link ConditionalOnAvailableEndpoint}).</li>
 * </ul>
 * The core {@code wiredoctor-autoconfigure} artifact never imports actuator;
 * this module is the only place the dependency appears.
 *
 * @author Deendayal Kumawat
 * @since 0.4.0
 */
@AutoConfiguration
@ConditionalOnClass(Endpoint.class)
public class WireDoctorActuatorAutoConfiguration {

    /**
     * @param analyzer     the core analyzer bean supplying the report
     * @param ghostTracker the ghost-tracking state when the user opted in via
     *                     {@code wiredoctor.ghost-tracking.enabled=true};
     *                     absent (null) in the default passive configuration —
     *                     the endpoint then answers {@code /ghosts} with a
     *                     DISABLED placeholder
     * @return the WireDoctor actuator endpoint
     */
    @Bean
    @ConditionalOnBean(WireDoctorAnalyzer.class)
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = WireDoctorEndpoint.class)
    public WireDoctorEndpoint wireDoctorEndpoint(WireDoctorAnalyzer analyzer,
                                                 ObjectProvider<WireDoctorGhostTracker> ghostTracker) {
        return new WireDoctorEndpoint(analyzer, ghostTracker.getIfAvailable());
    }
}
