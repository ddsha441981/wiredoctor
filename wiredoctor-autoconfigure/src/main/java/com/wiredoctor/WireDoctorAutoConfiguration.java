/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
@EnableConfigurationProperties(WireDoctorProperties.class)
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
     * @param properties the typed {@code wiredoctor.*} configuration
     * @return the {@link WireDoctorAnalyzer} instance
     */
    @Bean
    public WireDoctorAnalyzer wireDoctorAnalyzer(WireDoctorProperties properties) {
        return new WireDoctorAnalyzer(properties);
    }

    /**
     * Opt-in ghost tracking (v0.6.0 Phase 2) — the ONLY intrusive feature in
     * WireDoctor, guarded by an explicit opt-in with no {@code matchIfMissing}.
     * <p>
     * In the default configuration this whole class does not match, so the
     * tracking {@code BeanPostProcessor} is <b>never registered</b> and the
     * artifact stays 100% passive — the passivity regression test asserts
     * exactly that.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "wiredoctor.ghost-tracking.enabled", havingValue = "true")
    static class GhostTrackingConfiguration {

        /**
         * @return the shared tracking state proxies flip into and reports read from
         */
        @Bean
        public WireDoctorGhostTracker wireDoctorGhostTracker() {
            return new WireDoctorGhostTracker();
        }

        /**
         * The counting-proxy post-processor. Declared {@code static} so the
         * BPP is created without forcing early initialization of this
         * configuration class; the exclude list is read from the
         * {@link Environment} directly because {@code @ConfigurationProperties}
         * binding is itself a BPP and must not be triggered this early.
         *
         * @param tracker     the shared tracking state
         * @param beanFactory used read-only for singleton-scope checks
         * @param environment source of {@code wiredoctor.ghost-tracking.exclude}
         * @return the ghost tracking post-processor
         */
        @Bean
        public static WireDoctorGhostTrackingPostProcessor wireDoctorGhostTrackingPostProcessor(
                WireDoctorGhostTracker tracker,
                ConfigurableListableBeanFactory beanFactory,
                Environment environment) {
            WireDoctorProperties.GhostTracking config = new WireDoctorProperties.GhostTracking();
            config.setExclude(environment.getProperty("wiredoctor.ghost-tracking.exclude"));
            return new WireDoctorGhostTrackingPostProcessor(
                    tracker, beanFactory, config.resolveExcludedBeans());
        }

        /**
         * @param tracker    the tracking state to snapshot at shutdown
         * @param properties supplies the output directory
         * @return the shutdown ghost-report writer
         */
        @Bean
        public WireDoctorGhostReportWriter wireDoctorGhostReportWriter(
                WireDoctorGhostTracker tracker, WireDoctorProperties properties) {
            return new WireDoctorGhostReportWriter(tracker, properties);
        }
    }
}
