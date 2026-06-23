/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * {@link AutoConfiguration Auto-configuration} for the WireDoctor diagnostic suite.
 * <p>
 * Registers the {@link WireDoctorAnalyzer} bean which hooks into the application
 * lifecycle to generate startup metrics, dependency graphs, and orphan bean reports.
 * <p>
 * Can be completely disabled in production environments by setting {@code wiredoctor.enabled=false}.
 *
 * @author Deendayal Kumawat
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnProperty(
    name = "wiredoctor.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class WireDoctorAutoConfiguration {

    /**
     * Provides the primary analyzer listener that processes startup metrics 
     * upon {@link org.springframework.boot.context.event.ApplicationReadyEvent}.
     * 
     * @return the {@link WireDoctorAnalyzer} instance
     */
    @Bean
    public WireDoctorAnalyzer wireDoctorAnalyzer() {
        return new WireDoctorAnalyzer();
    }
}
